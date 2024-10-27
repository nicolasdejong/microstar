# STATICS

Service that provides static content. The 'resources/static' directory
will be served from root. More targets can be configured, although
for equal names the static resources will win.

The configuration file on the settings server can hold the
following:

    app.config.statics:
      targets:
        - name: someDir
          target: /some/dir/somewhere/                # filesystem
        - name: someEndpoint
          target: http://localhost:3456/someEndpoint  # proxy target

