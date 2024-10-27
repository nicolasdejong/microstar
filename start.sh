#!/bin/bash
cd jars || exit
if [ ! -f "microstar-watchdog.jar" ]
then
    for f in microstar-watchdog*.jar
    do
      cp $f microstar-watchdog.jar
      break
    done
fi
if [ -f "../start_secret.sh" ]
then
    source ../start_secret.sh
else
    java -jar microstar-watchdog.jar var:app.config.dispatcher.url=http://localhost:8080 var:encryption.encPassword=devSecret
fi