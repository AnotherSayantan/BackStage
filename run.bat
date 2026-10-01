@echo off
title BackStage
cd /d "%~dp0"
echo Starting BackStage Desktop...
call gradlew.bat :composeApp:run
