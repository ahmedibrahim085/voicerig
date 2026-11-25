import os, sys, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'host'))
import voicerig_run as r

LOG = """POLICY registered
EVT capture_started 1000 uid=1
EVT play_start 2000 /x.pcm 1500ms
EVT play_end 3500
EVT capture_sound_start 4100
EVT capture_sound_end 6100
EVT play_start 9000 /x.pcm 900ms
EVT play_end 9900
garbage line
EVT capture_sound_start 10300
""".splitlines()

class T(unittest.TestCase):
    def test_parse_ignores_noise(self):
        ev = r.parse_events(LOG)
        self.assertEqual([e[0] for e in ev][:3], ['capture_started', 'play_start', 'play_end'])
        self.assertEqual(len(ev), 8)
    def test_ttfa_and_reply_length(self):
        t = r.turn_metrics(r.parse_events(LOG))
        self.assertEqual(t[0], {'utterance_end_ms': 3500, 'ttfa_ms': 600, 'reply_ms': 2000})
    def test_unfinished_reply_has_ttfa_but_no_length(self):
        t = r.turn_metrics(r.parse_events(LOG))
        self.assertEqual((t[1]['ttfa_ms'], t[1]['reply_ms']), (400, None))
    def test_no_reply_is_none(self):
        t = r.turn_metrics(r.parse_events(['EVT play_end 100']))
        self.assertEqual(t, [{'utterance_end_ms': 100, 'ttfa_ms': None, 'reply_ms': None}])
    def test_sound_before_the_utterance_ended_is_not_a_reply_to_it(self):
        t = r.turn_metrics(r.parse_events(['EVT capture_sound_start 50', 'EVT play_end 100']))
        self.assertIsNone(t[0]['ttfa_ms'])

if __name__ == '__main__':
    unittest.main()
