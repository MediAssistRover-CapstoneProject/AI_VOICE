"""Web entry points for the native robot service and the local Studio."""

__all__ = ["app", "start_server"]


def __getattr__(name):
    if name not in __all__:
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
    # Importing robot_api must not load Studio's local documents or audio runtime.
    from src.web.app import app as studio_app, start_server

    globals().update(app=studio_app, start_server=start_server)
    return globals()[name]
