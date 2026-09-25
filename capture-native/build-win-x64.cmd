@echo off
rem Builds capture_dxgi.dll (Windows x64) with MSVC. Run from capture-native\.
rem Requires: VS 2022 + Windows 10/11 SDK. Copies the DLL into the Java module resources.
setlocal
rem Skip vcvarsall when cl is already on PATH (CI uses msvc-dev-cmd).
where cl >nul 2>&1
if errorlevel 1 (
  if exist "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvarsall.bat" (
    call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvarsall.bat" x64 || exit /b 1
  ) else if exist "C:\Program Files\Microsoft Visual Studio\2022\Enterprise\VC\Auxiliary\Build\vcvarsall.bat" (
    call "C:\Program Files\Microsoft Visual Studio\2022\Enterprise\VC\Auxiliary\Build\vcvarsall.bat" x64 || exit /b 1
  ) else if exist "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvarsall.bat" (
    call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvarsall.bat" x64 || exit /b 1
  ) else (
    echo cl.exe not found and no vcvarsall.bat located. Install VS 2022 + Windows SDK.
    exit /b 1
  )
)
if not defined JAVA_HOME (
  echo JAVA_HOME not set ^(point it at a JDK^)
  exit /b 1
)
set SDK_INC=C:\Program Files (x86)\Windows Kits\10\Include\10.0.22621.0
set SDK_LIB=C:\Program Files (x86)\Windows Kits\10\Lib\10.0.22621.0
cl /nologo /O2 /EHsc /MT /LD /DCAPTURE_DXGI_BUILD /DUNICODE /D_UNICODE ^
  /I include /I "%JAVA_HOME%\include" /I "%JAVA_HOME%\include\win32" ^
  /I "%SDK_INC%\um" /I "%SDK_INC%\shared" /I "%SDK_INC%\ucrt" ^
  src\dxgi_bridge.cpp ^
  /link d3d11.lib dxgi.lib user32.lib /LIBPATH:"%SDK_LIB%\um\x64" /LIBPATH:"%SDK_LIB%\ucrt\x64" ^
  /OUT:build\capture_dxgi.dll || exit /b 1
del dxgi_bridge.obj dxgi_bridge.lib dxgi_bridge.exp 2>nul
copy /Y build\capture_dxgi.dll "..\capture-backend-win-dxgi\src\main\resources\natives\win-x64\capture_dxgi.dll" || exit /b 1
echo OK: capture_dxgi.dll built and staged.
