@echo off
title BackStage (OpenGL Rendering)
cd /d "%~dp0"
echo Starting BackStage with SKIKO_RENDER_API=OPENGL...
set SKIKO_RENDER_API=OPENGL
call gradlew.bat :composeApp:run
