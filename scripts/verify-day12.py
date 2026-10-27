"""第五步真实网关验收。使用独立验收题目/活动，记录到 logs/day12-backend/report.json。"""
import json
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = 'http://127.0.0.1:8080'
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'logs/day12-backend/report.json'
checks, fixtures = [], {}


def sql(statement):
    result = subprocess.run(['docker', 'exec', '-i', 'oj-cloud-mysql', 'sh', '-c',
        'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -N -B oj_cloud'],
        input=statement, capture_output=True, text=True, encoding='utf-8', check=True)
    return result.stdout.strip()


def req(method, path, body=None, token=None, expected=200):
    headers = {'Content-Type': 'application/json'}
    if token: headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            status, env = response.status, json.load(response)
    except urllib.error.HTTPError as error:
        status, env = error.code, json.loads(error.read())
    check(method + ' ' + path + ' -> ' + str(expected), status == expected and env.get('code') == expected, env.get('message'))
    if expected == 200 and status != 200: raise RuntimeError(env)
    return env.get('data')


def check(name, passed, detail=None):
    checks.append({'name': name, 'pass': bool(passed), 'detail': detail})
    print(('PASS ' if passed else 'FAIL ') + name + (': ' + str(detail) if detail and not passed else ''), flush=True)


def login(name):
    return req('POST', '/api/auth/login', {'username': name, 'password': '123456'})['accessToken']


def wait(predicate, seconds=35):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        result = predicate()
        if result: return result
        time.sleep(.5)
    return None


CODE_A = '''#include <iostream>
using namespace std;
int main() {
    int n; cin >> n;
    long sum = 0;
    for (int i=0;i<n;i++) {
        int value; cin >> value;
        if (value > 0) sum += value;
    }
    cout << sum;
    return 0;
}
'''
CODE_B = CODE_A.replace('for (int i=0;i<n;i++) {', 'int i=0; while(i<n) {').replace('if (value > 0) sum += value;', 'if (value > 0) sum += value; i++;').replace('sum', 'answer').replace('value', 'input')

try:
    teacher, student, admin = login('T001'), login('20220101'), login('admin')
    classes = req('GET', '/api/teaching/classes', token=teacher)['list']
    class_id = classes[0]['classId']
    for name, real in [('s5demoa', '第五步验收甲'), ('s5demob', '第五步验收乙')]:
        sql(f"INSERT IGNORE INTO sys_user(username,password,real_name,role,class_id,status) SELECT '{name}',password,'{real}','STUDENT',{int(class_id)},1 FROM sys_user WHERE username='20220101';")
    user_a, user_b = login('s5demoa'), login('s5demob')
    fixtures['usernames'] = ['s5demoa', 's5demob']
    body = {'title': '第五步验收-正数累加', 'difficulty': 2, 'tags': ['循环'], 'statement': '输入n及n个整数，输出其中正数之和。',
            'timeLimitMs': 1000, 'memoryLimitMb': 256, 'aiEnabled': True, 'aiQuotaLimit': 3, 'aiStages': [1, 3],
            'codeTemplates': {'cpp': '#include <iostream>\nint main(){\n\n}\n'}}
    created = req('POST', '/api/problem/questions', body, teacher)
    pid, version = created['id'], created['version']; fixtures['problemId'] = pid
    # 契约 02 §3.3：学生访问 state≠1 的题目返回 403（不是 404 —— 404 只用于「题目不存在」）
    req('GET', f'/api/problem/questions/{pid}', token=student, expected=403)
    req('GET', f'/api/problem/questions/{pid}/edit', token=student, expected=403)
    req('PUT', f'/api/problem/questions/{pid}', dict(body, version=version+10), teacher, expected=409)
    point = req('POST', f'/api/problem/questions/{pid}/test-cases', {'kind':'sample','input':'3\n1 -2 3\n','output':'4\n','score':50,'isPublic':True}, teacher)
    req('PUT', f'/api/problem/questions/{pid}/state', {'action':'publish'}, teacher, expected=400)
    req('PUT', f'/api/problem/test-cases/{point["id"]}', {'score':45}, teacher)
    r = req('GET', f'/api/problem/questions/{pid}/edit', token=teacher)
    items = [{'id':point['id'],'seqNo':1,'kind':'sample','input':'3\n1 -2 3\n','output':'4\n','score':50,'isPublic':True},
             {'seqNo':2,'kind':'edge','input':'3\n-5 -2 -1\n','output':'0\n','score':50,'isPublic':False}]
    saved = req('PUT', f'/api/problem/questions/{pid}/test-cases/batch', {'version':r['version'],'items':items}, teacher)
    check('批量测试点返回总分100', saved.get('totalScore') == 100)
    req('PUT', f'/api/problem/questions/{pid}/state', {'action':'publish'}, teacher)
    check('磁盘全文已物化', (ROOT/f'data/testcases/{pid}/1.in').read_text() == items[0]['input'])
    req('GET', f'/api/problem/questions/{pid}', token=student)
    req('PUT', f'/api/problem/questions/{pid}/state', {'action':'publish'}, teacher, expected=409)
    req('PUT', f'/api/problem/questions/{pid}/state', {'action':'archive'}, teacher)
    r = req('GET', f'/api/problem/questions/{pid}/edit', token=teacher)
    req('PUT', f'/api/problem/questions/{pid}', {'version':r['version'],'title':'不可改归档'}, teacher, expected=409)
    req('PUT', f'/api/problem/questions/{pid}/state', {'action':'restore'}, teacher)
    copied = req('POST', f'/api/problem/questions/{pid}/copy', {}, teacher)
    req('DELETE', f'/api/problem/questions/{copied["id"]}', token=teacher)
    req('POST', '/api/problem/questions/import', {'format':'json','dryRun':True,'items':[body]}, teacher)

    def activity(title, mode, duration=1200000):
        now = int(time.time()*1000)
        return req('POST', '/api/teaching/assignments', {'title':title,'modeType':mode,'classId':class_id,'problemIds':[pid],
            'startAt':now-2000,'dueAt':now+duration,'allowedAiStages':[] if mode==3 else [1,3],
            'aiQuotaPerProb':0 if mode==3 else 3,'tabSwitchLimit':3,'publishNow':False}, teacher)['id']

    aid = activity('第五步验收-作业', 2); fixtures['assignmentId'] = aid
    req('GET', f'/api/teaching/assignments/{aid}', token=user_a, expected=403)
    req('PUT', f'/api/teaching/assignments/{aid}', {'title':'第五步验收-可撤销作业'}, teacher)
    req('PUT', f'/api/teaching/assignments/{aid}/state', {'action':'publish'}, teacher)
    req('GET', f'/api/teaching/assignments/{aid}/problems', token=user_a)
    submitted = req('POST', f'/api/teaching/assignments/{aid}/submit', {}, user_a)
    check('撤销截止精确为5000ms', submitted['undoDeadlineAt']-submitted['submittedAt']==5000)
    req('DELETE', f'/api/teaching/assignments/{aid}/submit', token=user_a)
    req('POST', f'/api/teaching/assignments/{aid}/submit', {}, user_a)
    time.sleep(5.1)
    req('DELETE', f'/api/teaching/assignments/{aid}/submit', token=user_a, expected=409)
    req('GET', f'/api/teaching/assignments/{aid}/summary', token=teacher)
    req('GET', f'/api/teaching/assignments/{aid}/submissions', token=teacher)

    exam = activity('第五步验收-监考与混淆代码', 3); fixtures['examId'] = exam
    req('PUT', f'/api/teaching/assignments/{exam}/state', {'action':'publish'}, teacher)
    paper = req('GET', f'/api/teaching/exam/{exam}/paper', token=user_a)
    check('试卷下发真实题且AI关闭', paper['problems'][0]['problemId']==pid and paper['allowAiStages']==[])
    remaining = req('GET', f'/api/teaching/exam/{exam}/remaining', token=user_a)
    check('权威计时来自endAt-serverNow', abs(remaining['remainingMs']-(remaining['endAt']-remaining['serverNow']))<5)
    req('POST', f'/api/teaching/exam/{exam}/answer', {'problemId':pid,'language':'cpp','code':CODE_A,'clientSavedAt':100}, user_a)
    req('POST', f'/api/teaching/exam/{exam}/answer', {'problemId':pid,'language':'cpp','code':'old','clientSavedAt':99}, user_a, expected=409)
    report = req('POST', '/api/teaching/exam/tab-switch', {'assignmentId':exam,'problemId':pid,'awaySeconds':2.99}, user_a)
    check('离开少于3秒不累计', report['tabSwitchCount']==0)
    for n in range(1,4):
        report = req('POST', '/api/teaching/exam/tab-switch', {'assignmentId':exam,'problemId':pid,'awaySeconds':3.01}, user_a)
        check(f'第{n}次切屏计数和红标', report['tabSwitchCount']==n and report['redFlagTriggered']==(n>=3))
    roster = req('GET', f'/api/teaching/exam/{exam}/monitor', token=teacher)
    check('监考花名册返回异常标记', any(s.get('redFlag') and s['switchCount']==3 for s in roster['list']))
    req('GET', f'/api/teaching/exam/{exam}/monitor', token=user_a, expected=403)
    req('POST', f'/api/teaching/exam/{exam}/extend', {'minutes':5,'reason':'验收延时'}, teacher)
    for token, code in [(user_a,CODE_A),(user_b,CODE_B)]:
        req('POST', f'/api/teaching/exam/{exam}/answer', {'problemId':pid,'language':'cpp','code':code,'clientSavedAt':int(time.time()*1000)}, token)
        req('POST', f'/api/teaching/exam/{exam}/hand-in', {'confirm':True}, token)
    req('POST', f'/api/teaching/exam/{exam}/answer', {'problemId':pid,'language':'cpp','code':'late','clientSavedAt':int(time.time()*1000)}, user_a, expected=409)
    check('交卷后后台自动判题并同步双方满分（不轮询判题status）', bool(wait(lambda:sql(f'SELECT COUNT(*) FROM teach_student_score WHERE assignment_id={exam} AND max_score=100;')=='2',60)))
    req('POST', f'/api/judge/submit', {'problemId':pid,'assignmentId':exam,'language':'cpp','code':CODE_A}, user_a, expected=409)

    task = req('POST', '/api/teaching/plagiarism/tasks', {'assignmentId':exam,'problemIds':[pid],'language':'cpp','stage1SimThreshold':75,'stage2ChannelCode':'local-qwen','scope':'problem'}, teacher)
    fixtures['taskId'] = task['taskId']
    def finished():
        r = req('GET', f'/api/teaching/plagiarism/tasks/{task["taskId"]}', token=teacher)
        return r if r['state'] in ('DONE','FAILED') else None
    task_result = wait(finished,180)
    check('真实双阶段任务完成', task_result and task_result['state']=='DONE', task_result)
    pairs = req('GET', f'/api/teaching/plagiarism/pairs?assignmentId={exam}', token=teacher)['list']
    check('for改while加变量重命名被语义鉴定识别', bool(pairs) and pairs[0]['level']=='high' and pairs[0]['semSim']>=85, pairs)
    if pairs:
        rid = pairs[0]['reportId']; fixtures['reportId'] = rid
        detail = req('GET', f'/api/teaching/plagiarism/pairs/{rid}', token=teacher)
        check('双栏真代码行与高亮片段齐全', bool(detail['rows']) and bool(detail['segments']) and bool(detail['aiDiagnosis']))
        req('GET', f'/api/teaching/plagiarism/diff/{rid}', token=teacher)
        req('PUT', '/api/teaching/plagiarism/audit', {'reportId':rid,'auditStatus':1}, teacher)
        check('确认后双方清零并保存原满分', sql(f'SELECT COUNT(*) FROM teach_student_score WHERE assignment_id={exam} AND max_score=0 AND prev_max_score=100;')=='2')
        req('DELETE', f'/api/teaching/plagiarism/audit/{rid}', token=teacher)
        check('撤销后双方恢复原满分', sql(f'SELECT COUNT(*) FROM teach_student_score WHERE assignment_id={exam} AND max_score=100 AND prev_max_score IS NULL;')=='2')
        req('GET', f'/api/teaching/plagiarism/pairs/{rid}', token=user_a, expected=403)
    req('POST', f'/api/teaching/exam/{exam}/finish', {'autoSubmitUnsubmitted':True}, teacher)
    req('POST', f'/api/teaching/exam/{exam}/answer', {'problemId':pid,'language':'cpp','code':CODE_A,'clientSavedAt':int(time.time()*1000)}, student, expected=409)
except Exception as error:
    check('验收流程执行完整', False, str(error))
finally:
    OUT.parent.mkdir(parents=True,exist_ok=True)
    OUT.write_text(json.dumps({'passed':sum(c['pass'] for c in checks),'total':len(checks),'checks':checks,'fixtures':fixtures},ensure_ascii=False,indent=2),encoding='utf-8')
    print(f"{sum(c['pass'] for c in checks)}/{len(checks)} passed; {OUT}",flush=True)
raise SystemExit(0 if checks and all(c['pass'] for c in checks) else 1)
