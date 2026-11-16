import unittest
from section_template_cache import SectionTemplateCache
from direct_section_nbt import section_root_nbt

class TemplateTests(unittest.TestCase):
    def test_identical_sections_reuse_exact_bytes(self):
        cache=SectionTemplateCache()
        states=["minecraft:water"]*4096
        first=cache.encode(2,states)
        second=cache.encode(2,list(states))
        self.assertIs(first,second)
        self.assertEqual((cache.hits,cache.misses),(1,1))
    def test_mutations_and_coordinates_do_not_leak(self):
        cache=SectionTemplateCache()
        states=["minecraft:air"]*4096
        first=cache.encode(4,states)
        states[17]="minecraft:stone"
        changed=cache.encode(4,states)
        self.assertNotEqual(first,changed)
        self.assertEqual(changed,section_root_nbt(4,states))
        self.assertEqual(cache.misses,2)
if __name__=="__main__":unittest.main()
