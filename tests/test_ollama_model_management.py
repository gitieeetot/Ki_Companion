import json
import unittest
from unittest.mock import patch

from ki.services import activate_ollama_model


class FakeResponse:
    def __init__(self, value=None):
        self.value = value

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return json.dumps(self.value).encode("utf-8")


class OllamaModelManagementTests(unittest.TestCase):
    @patch("ki.services.urllib.request.urlopen")
    def test_unloads_other_managed_models_before_selecting_one(self, urlopen):
        urlopen.side_effect = [
            FakeResponse({
                "models": [
                    {"name": "qwen3:0.6b"},
                    {"name": "qwen3:1.7b"},
                    {"name": "moondream:latest"},
                    {"name": "unmanaged-model:latest"},
                ],
            }),
            FakeResponse(),
            FakeResponse(),
        ]

        activate_ollama_model("qwen3:0.6b")

        self.assertEqual(urlopen.call_count, 3)
        unload_requests = [
            json.loads(call.args[0].data.decode("utf-8"))
            for call in urlopen.call_args_list[1:]
        ]
        self.assertEqual(
            {request["model"] for request in unload_requests},
            {"qwen3:1.7b", "moondream:latest"},
        )
        self.assertTrue(all(request["keep_alive"] == 0 for request in unload_requests))


if __name__ == "__main__":
    unittest.main()
