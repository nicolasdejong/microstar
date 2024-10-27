@echo off

rem Bypass "Terminate Batch Job" prompt.
if "%~1"=="-FIXED_CTRL_C" (
   REM Remove the -FIXED_CTRL_C parameter
   SHIFT
) ELSE (
   REM Run the batch with <NUL and -FIXED_CTRL_C
   CALL <NUL %0 -FIXED_CTRL_C %*
   GOTO :EOF
)

cd jars
IF NOT EXIST microstar-watchdog.jar (
    FOR %%F IN (microstar-watchdog*.jar) DO (
     copy %%F microstar-watchdog.jar
     goto :break
    )
)
:break

rem The start_secret batch file is like the java line below, but with secrets that should not be in the versioning system
IF EXIST ..\start_secret.bat (
  CALL ..\start_secret.bat
) ELSE (
  java -jar microstar-watchdog.jar var:app.config.dispatcher.url=http://localhost:8080 var:encryption.encPassword=devSecret
)
