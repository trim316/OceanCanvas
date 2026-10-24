"""Run actual palette proof/read wiring, including mixed palettes and later-slice mutation."""
from pathlib import Path
import subprocess,tempfile
s=(Path(__file__).resolve().parents[2]/'production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text(encoding='utf-8')
a=s.index('\tprivate static boolean sourceWaterOnlyAuditSection(');b=s.index('\tprivate static PhysicalAuditAdvance advancePhysicalProfileAudit(',a)
helper=s[a:b].replace('net.minecraft.world.level.chunk.LevelChunkSection','Section')
a=s.index('\t\t\tint sectionIndex = chunk.getSectionIndex(y);',b);b=s.index('\t\t\tprocessedBlocks++;',a)
read=s[a:b]
java="""
import java.util.*;
class UniformWaterAudit {
 static class BlockState {}
 static class Block {BlockState state=new BlockState();BlockState defaultBlockState(){return state;}}
 static class Blocks {static Block WATER=new Block();}
 static class States {Set<BlockState> palette=new HashSet<>();boolean maybeHas(java.util.function.Predicate<BlockState> p){return palette.stream().anyMatch(p);}}
 static class Section {States states=new States();States getStates(){return states;}}
 static class Cursor {Cursor set(int x,int y,int z){return this;}}
 static class State {int x,z;Cursor cursor=new Cursor();}
 static class Chunk {
  Section section=new Section();BlockState cell;int reads;
  Section[] getSections(){return new Section[]{section};}int getSectionIndex(int y){return 0;}
  Section getSection(int i){return section;}BlockState getBlockState(Cursor p){reads++;return cell;}
 }
"""+helper+"""
 static BlockState read(Chunk chunk,byte[] sourceWaterSectionFacts){
  int y=0;State state=new State();
"""+read+"""
  return bs;
 }
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args){
  BlockState water=Blocks.WATER.defaultBlockState();
  BlockState[] states={water,new BlockState(),new BlockState(),new BlockState(),new BlockState(),new BlockState()};
  for(int mask=1;mask<64;mask++){
   var chunk=new Chunk();for(int i=0;i<6;i++)if((mask&(1<<i))!=0)chunk.section.states.palette.add(states[i]);
   boolean uniform=mask==1;check(sourceWaterOnlyAuditSection(chunk.section)==uniform);
   for(var cell:chunk.section.states.palette){chunk.cell=cell;chunk.reads=0;check(read(chunk,new byte[1])==cell);check(chunk.reads==(uniform?0:1));}
  }
  var chunk=new Chunk();chunk.cell=water;chunk.section.states.palette.add(water);
  check(read(chunk,new byte[1])==water && chunk.reads==0);
  chunk.cell=states[1];chunk.section.states.palette.add(states[1]);
  check(read(chunk,new byte[1])==states[1] && chunk.reads==1); // next invocation re-proves palette
 }
}
"""
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'UniformWaterAudit.java';p.write_text(java,encoding='utf-8');subprocess.run(['java',str(p)],check=True)
audit=s[s.index('\tprivate static PhysicalAuditAdvance advancePhysicalProfileAudit('):s.index('\tprivate static PhysicalAuditAdvance advancePhysicalProfileAudit(')+15000]
assert 'byte[] sourceWaterSectionFacts = new byte[chunk.getSections().length];' in audit
state=s[s.index('static final class PhysicalAuditState'):s.index('private record PhysicalAuditAdvance')]
assert 'sourceWaterSectionFacts' not in state
assert 'isCanonicalCanvasWaterAuditState(bs, y, waterTop)' in audit
assert 'isPhysicalAuditProtected(world, state.x, y, state.z, bs, state.preservedWholeBounds)' in audit
print('Actual uniform-water audit: 63 palette combinations, mixed-state fallback and later-slice mutation passed')
