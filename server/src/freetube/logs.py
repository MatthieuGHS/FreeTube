"""Structured logs: `<time> <level> <logger> <message> key=value...`, grep-able, no dependency.
Callers only pass ids and revs in `extra`, never tokens, cookies or URLs."""

import logging
import sys

# LogRecord attributes that are not `extra` fields.
_STANDARD = set(logging.makeLogRecord({}).__dict__) | {"message", "asctime", "color_message"}


class KeyValueFormatter(logging.Formatter):
    """`<time> <level> <logger> <message> key=value...`: grep-able, no dependency.
    Callers only pass ids and revs in `extra`, never tokens, cookies or URLs."""

    def format(self, record: logging.LogRecord) -> str:
        line = super().format(record)
        extras = {k: v for k, v in record.__dict__.items() if k not in _STANDARD}
        return " ".join([line, *(f"{k}={v}" for k, v in extras.items())])


def configure_logging() -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(KeyValueFormatter("%(asctime)s %(levelname)s %(name)s %(message)s"))
    root = logging.getLogger()
    if not root.handlers:  # uvicorn only configures its own loggers; tests bring caplog's
        root.addHandler(handler)
    root.setLevel(logging.INFO)
    logging.getLogger("botocore").setLevel(logging.WARNING)  # DEBUG would print signed URLs
