import unittest
from region_first_canvas import Canvas,plan,region_for_chunk,expected_chunks
class RegionFirstTests(unittest.TestCase):
    def test_full_canvas(self):
        canvas=Canvas(0,0,4000,4000)
        regions=plan(canvas)
        self.assertEqual(expected_chunks(canvas),62500)
        self.assertEqual(sum(map(len,regions.values())),62500)
        self.assertEqual(len(regions),64)
        self.assertEqual(len({world for chunks in regions.values() for _,world in chunks}),62500)
    def test_negative_coords(self):
        regions=plan(Canvas(-32,-32,64,64))
        self.assertEqual(len(regions),4)
        for region,entries in regions.items():
            for local,world in entries:
                self.assertEqual(region_for_chunk(*world),(region,local))
    def test_protection_is_fatal(self):
        with self.assertRaisesRegex(ValueError,"protected=1"):
            plan(Canvas(0,0,32,32),protected={(0,0)})
    def test_existing_is_fatal(self):
        with self.assertRaisesRegex(ValueError,"existing=1"):
            plan(Canvas(0,0,32,32),existing={(1,1)})
    def test_alignment(self):
        for args in [(1,0,32,32),(0,0,31,32),(0,0,0,32)]:
            with self.assertRaises(ValueError):Canvas(*args)
if __name__=="__main__":unittest.main()
