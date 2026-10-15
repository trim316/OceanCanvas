import unittest
from checkpoint_campaign import validate


class CampaignTest(unittest.TestCase):
    def setUp(self):
        self.data = dict(candidate_run_id='38663912443', checkpoint_run_id='', source_commit='a'*40,
                         build='v253.125.80', width='5000+20000', natural_border=False)

    def test_fresh_and_resume(self):
        self.assertEqual(validate(self.data)['natural_border'], 'false')
        self.data['checkpoint_run_id'] = '123'
        self.assertEqual(validate(self.data)['checkpoint_run_id'], '123')

    def test_invalid_launches(self):
        for name, value in [('candidate_run_id', ''), ('source_commit', 'main'), ('build', 'latest'),
                            ('width', 'arbitrary'), ('checkpoint_run_id', '../world'), ('natural_border', 'yes')]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                validate(dict(self.data, **{name:value}))


if __name__ == '__main__': unittest.main()
