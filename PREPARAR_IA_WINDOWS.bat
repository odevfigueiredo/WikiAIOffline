@echo off
setlocal
cd /d "%~dp0" || exit /b 1
where git >nul 2>nul || (
    echo ERRO: Instale o Git para Windows antes de continuar.
    exit /b 1
)
set "VENDOR=vendor\llama.cpp"
set "REVISION="
set /p REVISION=<"gradle\llama-cpp.commit"
if not defined REVISION exit /b 1
for %%p in (llama-android.patch llama-android-asset.patch llama-android-build.patch) do (
    if not exist "patches\%%p" (
        echo ERRO: Patch Android ausente em patches\%%p.
        exit /b 1
    )
)
if not exist "%VENDOR%\.git" (
    git init -q "%VENDOR%" || exit /b 1
    git -C "%VENDOR%" config core.autocrlf false || exit /b 1
    git -C "%VENDOR%" config core.longpaths true || exit /b 1
)
set "HEAD_REVISION="
for /f "delims=" %%i in ('git -C "%VENDOR%" rev-parse --verify HEAD 2^>nul') do set "HEAD_REVISION=%%i"
if /i not "%HEAD_REVISION%"=="%REVISION%" (
    echo Obtendo a revisao fixada do llama.cpp.
    git -C "%VENDOR%" fetch --depth 1 --no-tags https://github.com/ggml-org/llama.cpp.git "%REVISION%" || exit /b 1
    git -C "%VENDOR%" checkout --detach "%REVISION%" || exit /b 1
)
rem O patch de assets altera linhas do patch base. Confira a ponta da pilha primeiro.
git -C "%VENDOR%" apply --reverse --ignore-space-change --check "%CD%\patches\llama-android-asset.patch" >nul 2>nul
if errorlevel 1 (
    call :APPLY_PATCH "%CD%\patches\llama-android.patch" || exit /b 1
    call :APPLY_PATCH "%CD%\patches\llama-android-asset.patch" || exit /b 1
)
call :APPLY_PATCH "%CD%\patches\llama-android-build.patch" || exit /b 1
git -C "%VENDOR%" rev-parse --verify HEAD > LLAMA_CPP_COMMIT_USADO.txt || exit /b 1
echo Pronto. Para gerar o APK offline execute gradlew.bat :app:assembleOfflineDebug.
exit /b 0

:APPLY_PATCH
git -C "%VENDOR%" apply --reverse --ignore-space-change --check "%~1" >nul 2>nul
if not errorlevel 1 exit /b 0
git -C "%VENDOR%" apply --ignore-space-change "%~1"
exit /b %errorlevel%
