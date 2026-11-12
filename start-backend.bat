@echo off
rem Backend Spring Boot - http://127.0.0.1:8080/api
rem Tự tắt backend cũ (java) còn giữ cổng 8080 trước khi chạy
cd /d "%~dp0"
node scripts\free-port.cjs 8080 java.exe
cd /d "%~dp0backend"
call mvnw.cmd -q spring-boot:run
