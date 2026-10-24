"""Exercise actual conservative reaction palette helper and section-skip wiring."""
from pathlib import Path
import subprocess,tempfile
s=(Path(__file__).resolve().parents[2]/'production-src/src/main/java/net/oceancanvas/mod/worldgen/OceanCanvasSurfaceFlattener.java').read_text()
a=s.index('\tprivate static boolean reactionProductPossible('); b=s.index('\tprivate static FluidSettleRepair',a)
helper=s[a:b].replace('net.minecraft.world.level.chunk.LevelChunkSection','Section')
a=s.index('\t\t\t\tint sectionIndex = chunk.getSectionIndex(y);',b);b=s.index('\t\t\t\tBlockState old =',a)
read=s[a:b]
java="""
import java.util.*;
class ReactionPalette {
 static class BlockState {int id;BlockState(int n){id=n;}boolean is(BlockState b){return this==b;}}
 static class Blocks {static BlockState STONE=new BlockState(0),COBBLESTONE=new BlockState(1),OBSIDIAN=new BlockState(2);}
 static class States {Set<BlockState> palette=new HashSet<>();boolean maybeHas(java.util.function.Predicate<BlockState> p){return palette.stream().anyMatch(p);}}
 static class Section {States states=new States();States getStates(){return states;}}
 static class Chunk {Section[] sections=new Section[4];Chunk(){for(int i=0;i<4;i++)sections[i]=new Section();}int getSectionIndex(int y){return (y+32)>>4;}Section getSection(int i){return sections[i];}}
"""+helper+"""
 static int reads(Chunk chunk,int bottom,int waterTop) {byte[] reactionSectionFacts=new byte[4];int count=0;
  for(int y=bottom;y<=waterTop;y++) {
"""+read+"""
   count++;
  }return count;
 }
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args){
  BlockState[] states={Blocks.STONE,Blocks.COBBLESTONE,Blocks.OBSIDIAN,new BlockState(3),new BlockState(4),new BlockState(5)};
  for(int mask=1;mask<64;mask++) {Chunk c=new Chunk();for(var section:c.sections)for(int i=0;i<6;i++)if((mask&(1<<i))!=0)section.states.palette.add(states[i]);
   boolean possible=(mask&7)!=0;check(reactionProductPossible(c.sections[0])==possible);
   check(reads(c,-29,29)==(possible?59:0));
   c.sections[1].states.palette.add(Blocks.OBSIDIAN);check(reads(c,-29,29)==(possible?59:16));
  }
  Chunk c=new Chunk();check(reads(c,-29,29)==0);c.sections[0].states.palette.add(Blocks.STONE);check(reads(c,-29,29)==13);
 }
}
"""
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'ReactionPalette.java';p.write_text(java);subprocess.run(['java',str(p)],check=True)
print('Reaction palette: 63 combinations, negative Y section bounds, mixed fallback and next-invocation mutation passed')
