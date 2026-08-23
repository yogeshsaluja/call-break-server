@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  server startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables, and ensure extensions are enabled
setlocal EnableExtensions

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%..

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and SERVER_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS=

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\lib\server-0.1.0.jar;%APP_HOME%\lib\ai.jar;%APP_HOME%\lib\protocol.jar;%APP_HOME%\lib\engine.jar;%APP_HOME%\lib\ktor-server-cio-jvm-3.5.1.jar;%APP_HOME%\lib\ktor-server-websockets-jvm-3.5.1.jar;%APP_HOME%\lib\ktor-server-content-negotiation-jvm-3.5.1.jar;%APP_HOME%\lib\ktor-server-status-pages-jvm-3.5.1.jar;%APP_HOME%\lib\ktor-server-call-logging-jvm-3.5.1.jar;%APP_HOME%\lib\ktor-server-core-jvm-3.5.1.jar;%APP_HOME%\lib\kotlinx-coroutines-slf4j-1.11.0.jar;%APP_HOME%\lib\ktor-http-cio-jvm-3.5.1.jar;%APP_HOME%\lib\ktor-websocket-serialization-jvm-3.5.1.jar;%APP_HOME%\lib\ktor-serialization-jvm-3.5.1.jar;%APP_HOME%\lib\ktor-websockets-jvm-3.5.1.jar;%APP_HOME%\lib\ktor-http-jvm-3.5.1.jar;%APP_HOME%\lib\ktor-events-jvm-3.5.1.jar;%APP_HOME%\lib\ktor-network-jvm-3.5.1.jar;%APP_HOME%\lib\ktor-utils-jvm-3.5.1.jar;%APP_HOME%\lib\ktor-io-jvm-3.5.1.jar;%APP_HOME%\lib\kotlinx-coroutines-core-jvm-1.11.0.jar;%APP_HOME%\lib\kotlin-reflect-2.3.21.jar;%APP_HOME%\lib\kotlinx-serialization-json-jvm-1.11.0.jar;%APP_HOME%\lib\kotlinx-serialization-core-jvm-1.11.0.jar;%APP_HOME%\lib\kotlinx-io-core-jvm-0.9.0.jar;%APP_HOME%\lib\kotlinx-io-bytestring-jvm-0.9.0.jar;%APP_HOME%\lib\kotlin-stdlib-2.3.21.jar;%APP_HOME%\lib\logback-classic-1.5.13.jar;%APP_HOME%\lib\annotations-23.0.0.jar;%APP_HOME%\lib\logback-core-1.5.13.jar;%APP_HOME%\lib\slf4j-api-2.0.18.jar;%APP_HOME%\lib\config-1.4.9.jar;%APP_HOME%\lib\jansi-2.4.3.jar


@rem Execute server
@rem endlocal doesn't take effect until after the line is parsed and variables are expanded
@rem which allows us to clear the local environment before executing the java command
endlocal & "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %SERVER_OPTS%  -classpath "%CLASSPATH%" com.yogesh.callbreak.server.ApplicationKt %* & call :exitWithErrorLevel

:exitWithErrorLevel
@rem Use "%COMSPEC%" /c exit to allow operators to work properly in scripts
"%COMSPEC%" /c exit %ERRORLEVEL%
