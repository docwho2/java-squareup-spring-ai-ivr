#!/bin/sh
export LOG_APPENDER=ConsoleThread
mvn spring-boot:run -Dspring-boot.run.profiles=local
