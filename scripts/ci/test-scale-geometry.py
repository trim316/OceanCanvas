import unittest
from scale_geometry import ScaleGeometry


class ScaleGeometryTest(unittest.TestCase):
    def test_border_target_geometry(self):
        for requested, authored in ((2000, 2032), (5000, 5032), (10000, 10032), (20000, 20000)):
            geometry = ScaleGeometry(requested, True)
            self.assertEqual(geometry.authored_width, authored)
            radius = authored // 2
            chunks = len(range((-radius) // 16, (radius - 1) // 16 + 1)) ** 2
            self.assertEqual(geometry.chunks, chunks)
            self.assertEqual(geometry.command, f'oceancanvas pregen start {requested // 2} 0 0 confirm border')
            fields = geometry.checkpoint_fields()
            self.assertEqual(fields['requestedBlocks'], requested)
            self.assertEqual(fields['minBlockX'], -radius)
            self.assertEqual(fields['maxBlockZ'], radius - 1)

    def test_default_checkpoint_geometry_unchanged(self):
        geometry = ScaleGeometry(500)
        self.assertEqual(geometry.authored_width, 500)
        self.assertEqual(geometry.chunks, 1024)
        self.assertEqual(geometry.command, 'oceancanvas pregen start 250 0 0 confirm')
        self.assertEqual(geometry.checkpoint_fields(), {})

    def test_full_canvas_clip_keeps_actual_start_and_target_geometry(self):
        geometry = ScaleGeometry(20000, True)
        self.assertEqual(geometry.operation_width, 20032)
        self.assertEqual(geometry.authored_width, 20000)
        # Candidate69 START/audit use authored width rather than expanded request.
        source = __import__('pathlib').Path(__file__).with_name('production-scale.py').read_text()
        self.assertIn('widthBlocks={AUTHORED_WIDTH}', source)
        self.assertIn("'--expected-size-blocks', str(AUTHORED_WIDTH)", source)
        self.assertEqual(geometry.chunks, 1562500)
        self.assertNotEqual(geometry.checkpoint_fields(), ScaleGeometry(20000).checkpoint_fields())


if __name__ == '__main__': unittest.main()
