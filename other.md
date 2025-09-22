1) javac คืออะไร

javac = Java Compiler

ทำหน้าที่ คอมไพล์ (compile) ไฟล์ .java (source code) → กลายเป็นไฟล์ .class (bytecode)

2) .class คืออะไร

ไฟล์ .class ไม่ใช่โค้ดที่อ่านง่ายแบบ .java แล้ว แต่เป็น bytecode สำหรับ Java Virtual Machine (JVM)

JVM สามารถ โหลดและรัน ไฟล์ .class ได้โดยใช้คำสั่ง java---
------------------------------------------------------------------------------------------------------------------
จัดให้เลยครับ 🙌 ผมจะวาด **diagram ของการทำงาน `RandomAccessFile → FileChannel → MappedByteBuffer → Shared Slots`** ให้เห็นภาพ

---

## 🔹 Flow การทำงาน

```
 Process 1              Process 2              Process 3
    |                      |                      |
    |                      |                      |
RandomAccessFile       RandomAccessFile       RandomAccessFile
   ("rw")                 ("rw")                 ("rw")
    |                      |                      |
    | getChannel()         | getChannel()         | getChannel()
    v                      v                      v
  FileChannel  --------- Shared File ---------  FileChannel
 ("heartbeat_shared.dat")  (disk)          ("heartbeat_shared.dat")
    |                      |                      |
    | map(...)             | map(...)             | map(...)
    v                      v                      v
MappedByteBuffer <------ Memory Mapping ------> MappedByteBuffer
    |                      |                      |
    | write slot (PID=1)   | write slot (PID=2)   | write slot (PID=3)
    |                      |                      |
    +-------> Shared Memory Slots (ในไฟล์) <------+
```

---

## 🔹 Shared Memory Layout (`REGION_SIZE = 128`)

ไฟล์ `heartbeat_shared.dat` จะถูกแบ่งเป็น **slot** (128 byte ต่อ slot):

| Slot | สำหรับอะไร | Offset เริ่มต้น | ช่วง byte |
| ---- | ---------- | --------------- | --------- |
| 0    | Boss info  | 0               | 0–127     |
| 1    | PID 1      | 128             | 128–255   |
| 2    | PID 2      | 256             | 256–383   |
| 3    | PID 3      | 384             | 384–511   |

---

## 🔹 ทำไมมันเวิร์ก

1. **ทุก process เปิดไฟล์เดียวกัน** → เหมือนแชร์กระดาษกลางแผ่นเดียว
2. **`RandomAccessFile` + `FileChannel.map`** → เปลี่ยนไฟล์นี้ให้กลายเป็น memory ที่ map เข้า address space ของ process ทุกตัว
3. **แต่ละ process เขียนเฉพาะ slot ของตัวเอง** (`pid * REGION_SIZE`) → ไม่ชนกัน
4. **ทุก process สามารถอ่าน slot ของ process อื่นได้** → ใช้เป็น heartbeat monitor

---

📌 พูดง่าย ๆ

* `RandomAccessFile` = ปากกาที่ใช้เขียนไฟล์
* `FileChannel` = ท่อที่ต่อไปยังไฟล์
* `MappedByteBuffer` = กระดาษ memory ที่ทุก process เขียน/อ่านได้ทันที
* `Shared Slots` = ตารางที่แบ่งช่องให้แต่ละ process

------------------------------------------------------------------------------------------------------------------


ทำไมเราต้องมี `offset = otherPid * REGION_SIZE` และ process ถึงรู้ว่า **ต้องอ่านของใครจากตรงไหนใน shared memory**

---

## 🔹 หลักการของ shared memory

* ไฟล์ `heartbeat_shared.dat` ที่สร้างขึ้นจะถูกแบ่งออกเป็น **slot**
* แต่ละ process จะมี slot ของตัวเอง (เหมือนเป็นช่องเก็บข้อมูลส่วนตัวใน memory file)
* ขนาดของแต่ละ slot = `REGION_SIZE` (ในโค้ดกำหนดเป็น 128 byte)
* การจัดตำแหน่งใน memory = `slot index * REGION_SIZE`

---

## 🔹 ตัวอย่างถ้า `NUM_PROCESSES = 3`

* `REGION_SIZE = 128`
* เราจะได้ layout แบบนี้ในไฟล์ `heartbeat_shared.dat`:

| Process | Offset เริ่มต้น | ใช้ memory (byte) |
| ------- | --------------- | ----------------- |
| PID 1   | `1 * 128 = 128` | 128–255           |
| PID 2   | `2 * 128 = 256` | 256–383           |
| PID 3   | `3 * 128 = 384` | 384–511           |

(*ช่องที่ index 0 มักกันไว้ใช้สำหรับ boss election*)

---

## 🔹 เวลาส่ง heartbeat

* `HeartbeatSender` ของ process `pid` จะเขียนข้อความลง **slot ของตัวเอง**:

  ```java
  int offset = pid * REGION_SIZE;
  ```

  เช่น PID=2 → `offset = 2*128 = 256` → เขียน heartbeat ลงไปที่ byte 256–383

---

## 🔹 เวลาฟัง heartbeat

* `HeartbeatListener` ของ PID=1 จะวนดูทุก process (2..N) แล้วคำนวณ offset:

  ```java
  int offset = otherPid * REGION_SIZE;
  ```

  ถ้า otherPid=2 → อ่านที่ byte 256–383
  ถ้า otherPid=3 → อ่านที่ byte 384–511

---

## 🔹 ทำไม process รู้ได้ว่า slot ของใครอยู่ตรงไหน?

เพราะเรากำหนด **กติกาเดียวกัน** ไว้ตั้งแต่ต้น:

1. ทุก process ใช้ไฟล์เดียวกัน (`heartbeat_shared.dat`)
2. ขนาด slot ต่อ process คงที่ (`REGION_SIZE`)
3. index ของ slot = `pid`

ดังนั้นแค่รู้ `pid` → ก็สามารถคำนวณได้ว่า slot ของ process นั้นอยู่ตรงไหนใน memory

---

📌 สรุปสั้น ๆ

* `offset = pid * REGION_SIZE` = สูตรกำหนดตำแหน่งในไฟล์ให้ไม่ชนกัน
* ทุก process รู้สูตรนี้ → เลยรู้ว่าจะเขียน/อ่านของใครที่ตรงไหน
* ทำให้สามารถสื่อสารกันผ่าน shared memory ได้แบบเป็นระเบียบ

---------------------------------------------------------------------------------------------------------------------


```java
RandomAccessFile raf = new RandomAccessFile(SHARED_FILE, "rw");
```

---

## 🔎 อธิบายทีละส่วน

### 🔹 `RandomAccessFile`

* เป็นคลาสใน Java (`java.io.RandomAccessFile`)
* ใช้สำหรับ **อ่าน/เขียนไฟล์** โดยสามารถ:

  * อ่านทีละ byte
  * เขียนทีละ byte
  * **กระโดดไปตำแหน่งใด ๆ ในไฟล์ได้ทันที (seek)** → ไม่ต้องอ่านเรียงลำดับแบบ `FileInputStream` หรือ `FileReader`
* เหมาะมากกับการทำ **shared memory แบบ slot** (เพราะ process แต่ละตัวเขียนข้อมูลเฉพาะ slot ของตัวเอง)

---

### 🔹 พารามิเตอร์ `(SHARED_FILE, "rw")`

* `SHARED_FILE` คือชื่อไฟล์ (ในที่นี้คือ `"heartbeat_shared.dat"`)
* `"rw"` คือโหมดเปิดไฟล์:

  * `"r"` = read-only → อ่านได้อย่างเดียว
  * `"rw"` = read + write → **อ่านเขียนได้**
  * `"rws"` / `"rwd"` = เขียนแล้วบังคับ sync ทันที (ช้ากว่า)

**ดังนั้น `"rw"` = เปิดไฟล์ `heartbeat_shared.dat` เพื่อให้ process อ่านและเขียนได้**

---

### 🔹 ตัวแปร `raf`

* ตอนนี้ `raf` คืออ็อบเจ็กต์ที่ผูกกับไฟล์ `heartbeat_shared.dat`
* คุณสามารถสั่ง:

  ```java
  raf.seek(128);           // กระโดดไป byte ตำแหน่งที่ 128
  raf.write("Hello".getBytes());  // เขียน "Hello" ตรงตำแหน่งนั้น
  raf.seek(128);
  int b = raf.read();      // อ่าน 1 byte
  ```
* แต่ในโค้ดของคุณ → `raf` ไม่ถูกใช้ตรง ๆ
  คุณเอามาเพื่อสร้าง `FileChannel`

---

### 🔹 ใช้กับ `FileChannel`

```java
FileChannel channel = raf.getChannel();
this.buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, size);
```

* `raf.getChannel()` → ดึง **FileChannel** ของไฟล์นี้ออกมา
* แล้วใช้ `map(...)` → แม็พไฟล์เข้า **หน่วยความจำ (MappedByteBuffer)**
* ผลคือ process หลายตัวที่เปิดไฟล์เดียวกัน ก็แชร์หน่วยความจำกันได้

---

## ✅ สรุปง่าย ๆ

`RandomAccessFile raf = new RandomAccessFile(SHARED_FILE, "rw");`
\= เปิดไฟล์ `heartbeat_shared.dat` ในโหมดอ่าน+เขียน เพื่อให้ Java process ใช้เป็นฐาน (backing file) ของ **shared memory** ที่ process หลายตัวใช้ร่วมกัน

----------------------------------------------------------------------------------------------------------------------------------------------------------

ดีครับ ✨ คำว่า **`MappedByteBuffer`** เป็นหัวใจของ *shared memory* ที่คุณกำลังทำอยู่เลย

---

## 🔎 `MappedByteBuffer` คืออะไร

* เป็น **คลาสใน Java (java.nio.MappedByteBuffer)**
* เกิดจากการ **map (แม็พ) ไฟล์เข้ามาในหน่วยความจำ (memory)** ผ่าน `FileChannel.map(...)`
* เมื่อไฟล์ถูกแม็พ → จะได้ buffer ที่อ่าน/เขียนข้อมูลในไฟล์ได้ราวกับเป็น **อาร์เรย์ของ byte**
* ความพิเศษคือ:

  * การอ่าน/เขียนลงไปใน `MappedByteBuffer` = ข้อมูลในไฟล์เปลี่ยนทันที
  * และ process อื่นที่แม็พไฟล์เดียวกันก็จะ **เห็นข้อมูลตรงกัน** (นี่แหละ shared memory!)

---

## 🔹 วิธีสร้าง

```java
RandomAccessFile raf = new RandomAccessFile("heartbeat_shared.dat", "rw");
FileChannel channel = raf.getChannel();

// map ขนาด 512 bytes
MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, 512);
```

* `"rw"` → เปิดไฟล์ให้อ่าน+เขียนได้
* `map(FileChannel.MapMode.READ_WRITE, 0, 512)` →

  * READ\_WRITE = สามารถอ่านเขียนได้
  * `0` = offset เริ่มต้นในไฟล์
  * `512` = ขนาดที่ map เข้ามาใน memory

---

## 🔹 การใช้งาน

เหมือนทำงานกับ array ของ byte เลย

```java
// เขียนข้อความลง buffer
String msg = "PID:1 alive " + System.currentTimeMillis();
byte[] data = msg.getBytes(StandardCharsets.UTF_8);

buffer.position(128);            // เลื่อนไปตำแหน่ง offset = 128
buffer.put(data);                // เขียนข้อความลง memory (และไฟล์)

// อ่านข้อความกลับมา
byte[] readBytes = new byte[128];
buffer.position(128);
buffer.get(readBytes, 0, 128);

String msg2 = new String(readBytes, StandardCharsets.UTF_8).trim();
System.out.println("Read: " + msg2);
```

---

## 🔹 ทำไมต้องใช้ `MappedByteBuffer`?

1. **เร็วกว่า I/O ปกติ**
   เพราะข้อมูลไฟล์ถูก map อยู่ในหน่วยความจำ OS แล้ว
2. **หลาย process ใช้ร่วมกันได้**
   Process ไหน ๆ ที่เปิดไฟล์เดียวกันด้วย `MappedByteBuffer` → จะเห็นข้อมูลร่วมกันแบบ *real-time*
3. **เข้าถึงแบบ random access**
   อยากเขียน/อ่านตรง offset ไหนก็ได้ (ดีมากสำหรับการทำ slot per process)

---

## ✅ สรุปสั้น ๆ

`MappedByteBuffer` = พื้นที่หน่วยความจำที่แม็พมาจากไฟล์ → ทำให้ process หลายตัวเห็นไฟล์เดียวกันเสมือนเป็น **shared memory**

* คุณเขียน string ลง buffer → อีก process อ่านได้ทันที
* ใช้เป็นกลไกหลักของระบบ **heartbeat + boss election** ที่คุณทำอยู่

---





