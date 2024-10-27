# SECURITY

## Before you do any hashing or encryption

Hashing and encryption need encryption configuration that should be set **before** you generate
any hashes or encrypted values. Changing any of the encryption configuration afterwards will
make the existing hashes and encrypted values invalid. Values to configure can be found in
the Encryption.Settings class but most defaults should be ok. The only important things to
configure are:

    encryption:
      encPassword: "secret"  # most important. Used when encrypting
      settings:
        encSalt: "salt used when encrypting"
        hashSalt: "salt used when hashing"

Setting these values in the application.yml will make them end up in your versioning system
which you do not want because the whole point of encrypting values is to be able to store the
secrets in a versioning system safely. Instead, they should be provided at application start
using the -D parameters supported by the java command. For example:

    java -Dencryption.encPassword=secret -Dencryption.settings.hashSalt=hsalt

Changing the encPassword and encSalt values will make existing encrypted values invalid.
They can be re-encrypted by using the dashboard "Encryption" tab to decrypt with the old
settings and encrypt with the new settings.

Changing the hashSalt will make existing hashed values invalid. As hashing is one-way it
is not possible to re-hash for the new configuration settings.

## Dashboard

The dashboard on `/dashboard` (e.g. `http://localhost:8080/dashboard`) can be
used to start/stop services, edit configuration and hash/encrypt/decrypt values. It is only
accessible by admins. There are endpoints used to encrypt, decrypt and hash values (used by
the dashboard) that can be called as well. When calling them directly, make sure to include
an admin token in the http headers. If no user-roles are configured, all users are admins
(see 'Checking roles in code' below).

## Password hashes in the configuration

The `authentication.users` configuration holds a map of user to password hash. When no users
are configured, all users are allowed and given the ADMIN role. This is to prevent bootstrap
problems (i.e. not able to login because there is no admin user). So make sure you add an
'admin' users and add a password hash. To create a hash from a password, go to the
'Encryption' tab in the dashboard (only for admin users) and use the 'Password hashing function'
panel.

The `authentication.users` configuration is used by the authorization service so should be
put in the `authorization.yaml` configuration file.

(Note that `authentication.userRoles` should be known to all services and therefore should
be added in the `services.yaml` file)

## Encrypted values in configuration

The configuration can contain secrets that are stored encrypted and encoded base64.
To create an encrypted value, go to the 'Encryption' tab in the dashboard (only for admin users)
and use the 'Encryption of strings' panel to create encrypted strings from any string. Add them
to the configuration and prefix the value with '{cipher}'. So for example:

    some.configuration.path: "{cipher}L7bKtK+bI+SJf/EPgaYdMg=="

The '{cipher}' prefix is also used in Spring-Cloud-Config and added for compatibility.
It is also possible to use '!cipher!' instead, which has the same function but doesn't
require quotes in YAML files.

When a value cannot be decrypted (e.g. mangled value or wrong encPassword) it will remain
as-is, which is different than Spring-Cloud-Config which adds an 'invalid.' prefix to the
value path. A value that cannot be decrypted remains as-is, but a warning message will
be added to the log. Also in the dashboard -> settings tab the 'Verify' will show the error.
In the same tab, use 'Show service' to show the combined settings and search there for
'{cipher}' (or '!cipher!').

## Checking roles in code

Rest handler methods can add a `UserToken` type parameter that will contain the user token
for that user. It has data fields (id, name, email) and methods to check if a user has a
role (hasRole, hasAnyRole) or if a user is allowed (isGuest, isAdmin, noGuest, mustBeAdmin).

Alternatively rest handler methods can be annotated with @RequiresRole(role) which has the
same effect as calling UserToken.mustHaveRole(role).

Note that all users will be accepted for all roles when no user roles are configured.
This way when starting new you can start configuring as admin. Make sure to add yourself
as admin by editing the roles in services.yml. If you lock yourself out you need access to
the system that stores the services.yml file and edit it directly (the settings service
will pick it up).
