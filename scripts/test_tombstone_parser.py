import unittest
from tombstone_experiment import count_tombstones
class TombstoneParserTest(unittest.TestCase):
    def test_path_is_not_a_deletion_marker(self):
        self.assertEqual(0,count_tombstones({'sstables':{'/data/tombstone_trial/Data.db':[{'value':'live'}]}}))
    def test_counts_row_tombstones(self):
        self.assertEqual(1,count_tombstones({'tombstone':{'timestamp':123,'deletion_time':'now'},'shadowable_tombstone':{'timestamp':123}}))
if __name__=='__main__':unittest.main()
