import random
import unittest
from palette_encoder import encode_local_palette,decode_local_palette,signed_long_bytes

class PaletteEncodingTests(unittest.TestCase):
    def test_uniform(self):
        for state in ("minecraft:stone","minecraft:water","minecraft:air"):
            values=[state]*4096
            palette,bits,longs=encode_local_palette(values)
            self.assertEqual(palette,[state])
            self.assertEqual(bits,4)
            self.assertEqual(len(longs),256)
            self.assertEqual(decode_local_palette(palette,bits,longs,4096),values)
    def test_variable_ocean_floor(self):
        for seed in range(20):
            rng=random.Random(seed)
            floors=[rng.randrange(-8,40) for _ in range(256)]
            values=[]
            for y in range(-16,0):
                for z in range(16):
                    for x in range(16):
                        values.append("minecraft:stone" if y<floors[z*16+x] else "minecraft:water")
            palette,bits,longs=encode_local_palette(values)
            self.assertEqual(decode_local_palette(palette,bits,longs,4096),values)
            self.assertEqual(len(signed_long_bytes(longs)),8*len(longs))
    def test_padded_boundary(self):
        values=[f"s{i%17}" for i in range(4096)]
        palette,bits,longs=encode_local_palette(values)
        self.assertEqual(bits,5)
        self.assertEqual(len(longs),342)
        self.assertEqual(decode_local_palette(palette,bits,longs,4096),values)
    def test_reject_oversized_palette(self):
        with self.assertRaises(ValueError):
            encode_local_palette([str(i) for i in range(257)])
if __name__=="__main__":unittest.main()
