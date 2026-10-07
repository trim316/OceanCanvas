"""Inspect actual persisted aquatic blocks, support, and water-column biome."""
from pathlib import Path
import sys, json, zipfile, io, zlib, gzip, math
sys.path.insert(0, str(Path(__file__).parent/'python-deps'))
import nbtlib

artifact = Path(sys.argv[1])
out = Path(sys.argv[2])
plants = {'minecraft:seagrass', 'minecraft:tall_seagrass', 'minecraft:kelp', 'minecraft:kelp_plant'}
counts = {p: 0 for p in plants}
samples, invalid, sampled_chunks = [], [], []
biome_counts = {}

def decode(container, count, minimum):
    palette = container['palette']
    if len(palette) == 1:
        return [palette[0]] * count
    bits = max(minimum, (len(palette)-1).bit_length())
    per_long = 64 // bits
    values = container['data']
    mask = (1 << bits)-1
    return [palette[((int(values[i//per_long]) & ((1<<64)-1)) >> ((i%per_long)*bits)) & mask] for i in range(count)]

with zipfile.ZipFile(artifact) as archive:
    for name in sorted(archive.namelist()):
        if '/world/' not in name or '/region/r.' not in name or not name.endswith('.mca'):
            continue
        region = archive.read(name)
        for i in range(1024):
            sector = int.from_bytes(region[i*4:i*4+3], 'big')
            if not sector:
                continue
            start = sector * 4096
            length = int.from_bytes(region[start:start+4], 'big')
            compression = region[start+4]
            payload = region[start+5:start+4+length]
            raw = {1:gzip.decompress, 2:zlib.decompress, 3:lambda v:v}[compression](payload)
            chunk = nbtlib.File.parse(io.BytesIO(raw))
            cx, cz = int(chunk['xPos']), int(chunk['zPos'])
            if not (-32 <= cx < 32 and -32 <= cz < 32):
                continue
            containers = {}
            sections = {}
            for section in chunk.get('sections', []):
                if 'block_states' in section and -2 <= int(section['Y']) <= 3:
                    containers[int(section['Y'])] = section['block_states']
                    if any(str(p['Name']) in plants for p in section['block_states']['palette']):
                        sections[int(section['Y'])] = decode(section['block_states'],4096,4)
            def block(x,y,z):
                if y//16 not in sections and y//16 in containers:
                    sections[y//16] = decode(containers[y//16],4096,4)
                values = sections.get(y//16)
                if values is None:
                    return 'minecraft:air'
                return str(values[(y%16)*256+z*16+x]['Name'])
            local_counts = {p:0 for p in plants}
            for sy, values in list(sections.items()):
                if sy < -2 or sy > 3:
                    continue
                for index, state in enumerate(values):
                    p = str(state['Name'])
                    if p not in plants:
                        continue
                    x, z, y = index%16, (index//16)%16, sy*16+index//256
                    wx,wz = cx*16+x,cz*16+z
                    if not (-500 <= wx < 500 and -500 <= wz < 500 and -25 < y <= 62):
                        continue
                    below = block(x,y-1,z)
                    counts[p] += 1
                    local_counts[p] += 1
                    valid = below not in {'minecraft:air','minecraft:water'}
                    if p in {'minecraft:kelp','minecraft:kelp_plant'}:
                        valid &= below in {'minecraft:kelp','minecraft:kelp_plant','minecraft:sand','minecraft:gravel','minecraft:dirt','minecraft:stone','minecraft:clay','minecraft:deepslate','minecraft:bedrock'}
                    if not valid:
                        invalid.append({'x':wx,'y':y,'z':wz,'plant':p,'below':below})
                    if len(samples)<48:
                        samples.append({'x':wx,'y':y,'z':wz,'plant':p,'below':below,'validSupport':valid})
            # Sample center of the created water column, distinct from underground.
            for section in chunk.get('sections', []):
                sy=int(section['Y'])
                if sy == 0 and 'biomes' in section and -500 <= cx*16+8 < 500 and -500 <= cz*16+8 < 500:
                    biomes=decode(section['biomes'],64,1)
                    b=str(biomes[2+2*4+2*16])
                    biome_counts[b]=biome_counts.get(b,0)+1
            if any(local_counts.values()):
                sampled_chunks.append({'x':cx,'z':cz,'plants':local_counts})
result={'scope':'persisted-1k-water-column-aquatic-block-support-and-biome-sample',
        'plantBlockCounts':counts,'vegetatedChunks':len(sampled_chunks),
        'invalidSupportCount':len(invalid),'invalidSupport':invalid[:50],
        'waterColumnCenterBiomeSamples':biome_counts,'plantSamples':samples,
        'representativeChunkSamples':sampled_chunks[::max(1,len(sampled_chunks)//24)][:24]}
result['passed']=not invalid and counts['minecraft:seagrass']+counts['minecraft:tall_seagrass']>0 and counts['minecraft:kelp']+counts['minecraft:kelp_plant']>0 and biome_counts.get('minecraft:ocean',0)>0 and set(biome_counts)=={'minecraft:ocean'}
out.write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if k not in {'plantSamples','representativeChunkSamples','invalidSupport'}},indent=2))
