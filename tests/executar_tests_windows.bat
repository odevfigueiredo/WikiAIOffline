@echo off
setlocal
cd /d "%~dp0.."
if not exist "build" mkdir build
set SRC=app\src\main\java\com\wikiaioffline
call kotlinc "%SRC%\SearchEngine.kt" "%SRC%\BilingualSearch.kt" "%SRC%\WikiRag.kt" tests\TestRag.kt tests\TestBilingual.kt -include-runtime -d build\testes_local.jar
if errorlevel 1 exit /b 1
java -cp build\testes_local.jar TestRagKt
if errorlevel 1 exit /b 1
java -cp build\testes_local.jar TestBilingualKt
