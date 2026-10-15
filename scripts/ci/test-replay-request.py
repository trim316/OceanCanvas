import unittest
from replay_request import validate

class RequestTest(unittest.TestCase):
    def setUp(self):
        self.case=dict(name='control',run='123',source='a'*40,build='v253.125.80')
        self.data=dict(parent_run_id='123',parent_artifact='endurance-checkpoint-5000',seconds=180,candidates=[self.case])

    def test_valid(self):
        self.assertEqual(validate(self.data)['seconds'],'180')

    def test_invalid(self):
        for key,value in [('parent_run_id','main'),('parent_artifact','../world'),('seconds',0),('candidates',[])]:
            with self.subTest(key=key),self.assertRaises(ValueError):validate(dict(self.data,**{key:value}))
        with self.assertRaises(ValueError):validate(dict(self.data,candidates=[self.case,self.case]))
        with self.assertRaises(ValueError):validate(dict(self.data,candidates=[dict(self.case,source='main')]))

if __name__=='__main__':unittest.main()
