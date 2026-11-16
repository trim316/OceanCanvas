import unittest
from expansion_region_planner import Bounds, square, plan_expansion

class ExpansionPlannerTests(unittest.TestCase):
    def test_no_reprocessing_original_canvas(self):
        a = square(0, 0, 64)
        b = square(0, 0, 128)
        p = plan_expansion(a, b)
        self.assertEqual(len(p.pending), 64-16)
        self.assertFalse(any(a.contains_chunk(x,z) for x,z in p.pending))
    def test_protected_verified_and_existing_exclusions(self):
        a = square(0,0,32); b = square(0,0,96)
        p = plan_expansion(a,b, protected={(2,2)}, verified={(-2,-2)},
                           already_generated={(2,1)})
        self.assertNotIn((2,2),p.pending)
        self.assertNotIn((-2,-2),p.pending)
        self.assertNotIn((2,1),p.pending)
        self.assertIn((2,1),p.existing_generated_pending)
        self.assertIn((2,1),plan_expansion(a,b,already_generated={(2,1)},
                                           allow_conversion=True).pending)
    def test_negative_region_floor_division(self):
        a=Bounds(-16,-16,16,16); b=Bounds(-32,-32,16,16)
        p=plan_expansion(a,b)
        self.assertIn((-2,-2),p.by_region()[(-1,-1)])
    def test_reject_shrink(self):
        with self.assertRaises(ValueError):
            plan_expansion(square(0,0,128),square(0,0,64))
    def test_reject_misaligned(self):
        with self.assertRaises(ValueError): Bounds(1,0,32,32)
    def test_stable_repeat(self):
        a=square(0,0,32); b=square(0,0,96)
        self.assertEqual(plan_expansion(a,b).by_region(),plan_expansion(a,b).by_region())
    def test_region_boundary(self):
        a=Bounds(0,0,512,512); b=Bounds(0,0,528,512)
        self.assertIn((32,0),plan_expansion(a,b).by_region()[(1,0)])
if __name__ == "__main__": unittest.main()
