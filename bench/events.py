"""持续监听 Docker 日志，不靠低频轮询判断完成。"""
from datetime import datetime,timezone
import json
import re
import subprocess
import threading
import time
from .server import SCRIPTS

FINISHED=re.compile(r'Task finished for minecraft:overworld\. Processed: ([\d,]+) chunks.*Total time: (\d+):(\d+):(\d+)')

class LogStream:
    def __init__(self,path):
        self.path=path;self.rows=[];self.condition=threading.Condition();self.error=None
        since=datetime.now(timezone.utc).isoformat()
        self.process=subprocess.Popen(['bash','-c','source "$1"; tc logs --follow --no-color --no-log-prefix --timestamps --since="$2" minecraft',
                                       'bash',str(SCRIPTS/'lib.sh'),since],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
        self.thread=threading.Thread(target=self._read,daemon=True);self.thread.start()
    def _read(self):
        try:
            with self.path.open('a') as out:
                for line in self.process.stdout:
                    row=dict(time_ns=time.monotonic_ns(),realtime_ns=time.time_ns(),line=line.rstrip())
                    with self.condition:
                        self.rows.append(row)
                        out.write(json.dumps(row)+'\n');out.flush()
                        self.condition.notify_all()
        except Exception as exc:self.error=exc
    def mark(self):
        with self.condition:return len(self.rows)
    def wait_task(self,mark,timeout=900):
        deadline=time.monotonic()+timeout;progress=time.monotonic();cursor=mark
        with self.condition:
            while time.monotonic()<deadline:
                for row in self.rows[cursor:]:
                    if '[Chunky]' in row['line']:progress=time.monotonic()
                    match=FINISHED.search(row['line'])
                    if match:
                        stamp=row['line'].split()[0]
                        emitted=int(datetime.fromisoformat(stamp.replace('Z','+00:00')).timestamp()*1e9)
                        return dict(count=int(match[1].replace(',','')),elapsed_s=int(match[2])*3600+int(match[3])*60+int(match[4]),
                                    notification_latency_ns=max(0,row['realtime_ns']-emitted),**row)
                cursor=len(self.rows)
                if self.error:raise self.error
                if self.process.poll() is not None:raise RuntimeError('生成期间日志流断开')
                if time.monotonic()-progress>120:raise TimeoutError('生成任务 120 秒无进度')
                self.condition.wait(.1)
        raise TimeoutError('生成任务超过 900 秒')
    def close(self):
        self.process.terminate()
        try:self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:self.process.kill();self.process.wait()
        self.thread.join(timeout=10)
        if self.thread.is_alive():raise RuntimeError('日志采集未结束')
        if self.error:raise self.error
