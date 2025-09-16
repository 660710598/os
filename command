cd .\thread\src
javac DistributedProcessMain.java DistributedLauncher.java
java DistributedLauncher

Get-CimInstance Win32_Process -Filter "name = 'java.exe'" | Select ProcessId,CommandLine

taskkill /PID <PID> /F
taskkill /IM java.exe /F   //kill all
