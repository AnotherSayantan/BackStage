@echo off
title BackStage Launcher
cd /d "%~dp0"
echo Starting BackStage Desktop...
call gradlew.bat :composeApp:run
