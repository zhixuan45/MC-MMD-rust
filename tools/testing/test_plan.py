import unittest

from plan import GROUPS, JAVA, RUST, check_inventory, select
from rust_batches import partition


class TestSelection(unittest.TestCase):
    def test_documentation_does_not_schedule_engines(self):
        self.assertEqual(([], []), select(['README.md', 'doc/PMX_VARIANTS.md']))

    def test_stage_change_keeps_the_relevant_batch(self):
        java, rust = select(['common/src/main/java/com/shiroha/mmdskin/stage/client/camera/StagePcmDecoder.java'])
        self.assertEqual(['stage'], java)
        self.assertEqual([], rust)

    def test_shared_java_and_build_changes_expand_coverage(self):
        for path in ['common/src/main/java/com/shiroha/mmdskin/config/ConfigData.java',
                     'common/src/main/resources/mmdskin.mixins.json']:
            self.assertEqual(sorted(JAVA), select([path])[0])
        self.assertEqual((sorted(JAVA), sorted(RUST)), select(['tools/testing/groups.json']))

    def test_native_changes_include_consumers_and_serial_cleanup(self):
        java, rust = select(['rust_engine/src/jni_bridge/native_func.rs'])
        self.assertEqual(sorted(JAVA), java)
        self.assertEqual(['model', 'serial'], rust)
        self.assertEqual(['animation', 'model'], select(['rust_engine/src/animation/vpd_file.rs'])[1])

    def test_full_mode_and_unknown_files_do_not_silently_skip_tests(self):
        self.assertEqual((sorted(JAVA), sorted(RUST)), select([], full=True))
        self.assertEqual((sorted(JAVA), sorted(RUST)), select(['new-build-tool.py']))

    def test_all_java_tests_have_one_owner(self):
        check_inventory()

    def test_rust_serial_test_cannot_run_in_a_parallel_batch(self):
        serial = GROUPS['rust_serial'][0]
        groups = partition(['model::runtime::tests::sample', serial])
        self.assertEqual(['model::runtime::tests::sample'], groups['model'])
        self.assertEqual([serial], groups['serial'])
        with self.assertRaises(ValueError):
            partition([serial, 'future_unassigned::test'])


if __name__ == '__main__':
    unittest.main()
