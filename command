javac -d out DistributedLauncher.java DistributedProcessMain.java
java -cp out DistributedLauncher

taskkill /PID <PID> /F
taskkill /IM java.exe /F   //kill all
