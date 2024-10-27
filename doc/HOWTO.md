# HOWTO

While this project tries to be as obvious as possible, there are still
many areas that may ask yourself "how to do that?". There may be answers
to these questions here.

Q: **How to start the services (dev)?**<br/>
A: In IntelliJ find the 'Services' tab (probably at the bottom somewhere). It should
show a list of Spring services found by IntelliJ. Select a service and press the
start button.

Q: **How to start the services (prod)?**<br/>
A: The build creates a 'jars/' folder that holds all jars. From there start the
   Watchdog that will start the dispatcher. The dispatcher will start the other
   services when called. So:

   First, build:

    mvn clean install

   Then start MicroStar by either:

    ./start.sh

   or do what is in the start script:

    cd jars/
    java -jar microstar-watchdog.jar

   Test if it started by going to http://localhost:8080/dashboard
   (or another port if you configured it differently).
   Sometimes a page reload is required.

Q: **How to start the admin frontend (dev)?**<br/>
A: Open a terminal in the /microstar-services/microstar-dispatcher/server/src/main/frontend/dashboard directory
and type 'npm run dev'. This will start the Svelte development environment. Then
go to http://localhost:8080 in your browser (assuming that is the port the
Dispatcher is running on). The Dispatcher has a fallback configuration to redirect
unknown traffic to http://localhost:5173, which is the Svelte/Vite dev server.
This way the client only sees localhost:8080. In production the compressed Svelte
code will be stored in the dispatcher service (built as part of Dispatcher).

Q: **How to start the admin frontend (prod)?**<br/>
The frontend code is built by and part of the Dispatcher so opening
http://localhost:8080/dashboard (default url) should load and open
the frontend once the Dispatcher has started.

Q: **How to create the banner.txt**<br/>
A: https://devops.datenkollektiv.de/banner.txt/index.html using 'standard' font<br/>

Q: **How to run all tests from IntelliJ?**<br/>
A: Collapse the project tree, then select all modules, right-click and select 'run tests'.
   The run configurations now should have a 'Whole project' JUnit item. Edit the run
   configurations, select the 'Whole Project' item and click the 'Save Configuration'
   icon, after which it should no longer appear gray.

Q: **How to mock fields?**<br/>
A: Set the fields using ReflectionTestUtils.setField(mock, fieldName, fieldValue).
   This utils class is available from Spring boot. Mocking should almost never be
   needed. Use the DynamicPropertiesManager to set test properties before running
   the test. See the MicroStar tests for examples.

Q: **How to add secrets to the configuration in a secure way**<br/>
A: First make sure you have configuration for `encryption.password` and
  `encryption.settings`. Add users under `authentication.users` and
   user roles under `authentication.userRoles`. When no users are added,
   all logins will be accepted. If no roles are configured, all users
   will have the ADMIN role. Don't store the password in an application.yml
   but add it on the command-line when running the Watchdog or Dispatcher
   (when no Watchdog is used).
   See [SECURITY](SECURITY.md) for details and how to generate hashes
   and how to encrypt text (if encryption is deemed necessary as only
   ADMINs have access to the settings dashboard).
