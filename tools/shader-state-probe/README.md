# Shader state regression probe

Uses a hidden GLFW/OpenGL context and the real Minecraft `ShaderInstance`.
No game instance or native model is required.

```powershell
.\gradlew.bat :common:compileJava :common:writeSphereProbeClasspath -I tools/sphere-texture-probe/classpath.gradle
.\tools\shader-state-probe\run.ps1
```

To exercise the renderer classes from a packaged NeoForge JAR:

```powershell
.\tools\shader-state-probe\run.ps1 -Jar .\neoforge\build\libs\mmdskin-neoforge-1.10alpha-1.21.1.jar
```

The probe first reproduces the stale `ShaderInstance` cache and deliberately
consumes the expected `GL_INVALID_OPERATION`. It then invokes both production
CPU and GPU `renderToon` methods with incomplete model instances to exercise
their exception cleanup. After each pass it checks that the host shader binds
again and that a uniform upload succeeds with the expected value. The same
backend checks fail before the cleanup fix.

This covers the actual teardown paths and GL program state. It does not launch
Minecraft, render a complete model, or exercise a live Iris/NeOculus pipeline.
