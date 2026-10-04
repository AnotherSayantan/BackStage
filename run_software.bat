@echo off
title BackStage (Software Rendering)
cd /d "%~dp0"
echo Starting BackStage with SKIKO_RENDER_API=SOFTWARE...
set SKIKO_RENDER_API=SOFTWARE
call gradlew.bat :composeApp:run
