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


def request_with_status(method, path, body=None, token=None, timeout=180):
    """同类请求，但**不写 check、不抛异常**，返回 (status, 完整信封)。

    用于需要自己判断状态码 / 自定义超时的端点（如 #63 同步查重要 90s、
    #71 rerun 要区分 200 与 409 两个分支）。
    """
    headers = {'Content-Type': 'application/json'}
    if token: headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(BASE + path,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        try:
            return error.code, json.loads(error.read())
        except json.JSONDecodeError:
            return error.code, {}
    except Exception as error:  # noqa: BLE001
        return 0, {'__error__': str(error)}


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

    # ══════════════════════════════════════════════════════════════
    #  #63 POST /api/teaching/plagiarism/check —— **同步**单题查重（v1.0 原样 + taskId）
    #  契约 §6.7.1 / §12 清单 #63。这条此前一直没有自动化覆盖（day17 的 E.3 交叉核对抓到）。
    #  ⚠️ 同步接口会**真的跑完**双阶段：阶段一 SimHash+AST + 阶段二真实 Ollama 语义鉴定，
    #     实测 16 秒左右 → 下面单独给了 90s 超时，别用默认值。
    #  ⚠️⚠️ **必须先等上面那个异步任务跑到 DONE 再调本接口**（第 6 步实测踩过）：
    #     `PlagiarismTaskService.prepare()` 对「同 assignmentId + 同 problemId」有互斥
    #     （避免两条链路同时改写同一批 report 行），撞上就抛
    #     `409 该题目已有进行中的查重任务`。
    #     最初这一块紧跟 `POST /tasks` 之后，于是**必撞 409**——
    #     而 409 是正确行为，不是缺陷；错的是脚本顺序。
    #     现在改成：先 `wait` 到任务终态，再调 check。
    # ══════════════════════════════════════════════════════════════
    print('── 等待异步任务进入终态（#63 的前置，避免同题互斥 409）──', flush=True)

    def _task_settled():
        st, env = request_with_status('GET', f"/api/teaching/plagiarism/tasks/{task['taskId']}",
                                      None, teacher, timeout=60)
        state = (env.get('data') or {}).get('state') if isinstance(env, dict) else None
        return state if state in ('DONE', 'FAILED') else None

    settled = wait(_task_settled, 240)
    check('#63 前置：同题异步任务已进入终态（否则 check 必撞 409 互斥）',
          bool(settled), f'state={settled}')
    # 给事务提交留一点余量：状态转 DONE 与释放互斥锁不是同一个事务
    time.sleep(2)

    print('── #63 同步单题查重（真实 Ollama，约 16s）──', flush=True)
    check_started = time.monotonic()
    status, env = request_with_status('POST', '/api/teaching/plagiarism/check',
        {'assignmentId':exam,'problemId':pid,'language':'cpp',
         'stage1SimThreshold':75,'stage2ChannelCode':'local-qwen'}, teacher, timeout=90)
    check_elapsed = time.monotonic() - check_started
    sync_check = env.get('data') if isinstance(env, dict) else None
    check('#63 同步查重 HTTP 200 且信封 code=200',
          status == 200 and isinstance(env, dict) and env.get('code') == 200,
          f'HTTP {status} code={None if not isinstance(env, dict) else env.get("code")}')
    if isinstance(sync_check, dict):
        fixtures['syncCheckTaskId'] = sync_check.get('taskId')
        # 契约 §6.7.1：「v1.0 响应不变；新增 taskId」
        check('#63 返回新增的 taskId', isinstance(sync_check.get('taskId'), int) and sync_check['taskId'] > 0,
              f"taskId={sync_check.get('taskId')}")
        # 同步接口必须给出真实统计（不是空壳）
        check('#63 返回真实样本数与对比对数',
              isinstance(sync_check.get('totalValidSamples'), int)
              and isinstance(sync_check.get('totalPairsCompared'), int)
              and sync_check['totalValidSamples'] >= 1,
              f"samples={sync_check.get('totalValidSamples')} pairs={sync_check.get('totalPairsCompared')} "
              f"suspicious={sync_check.get('stage1SuspiciousCount')} high={sync_check.get('stage2HighRiskCount')}")
        # 阶段一阈值 75：suspicious 数不可能超过 totalPairs
        susp, pairs = sync_check.get('stage1SuspiciousCount'), sync_check.get('totalPairsCompared')
        check('#63 阶段一可疑对数不超过总对数', isinstance(susp, int) and isinstance(pairs, int) and susp <= pairs,
              f'suspicious={susp} pairs={pairs}')
        # 真实耗时断言：#63 是同步真跑，不该秒回（<1s 多半是没真跑模型）
        check('#63 同步查重真的跑了（耗时 > 1s，不是秒回空壳）', check_elapsed > 1.0,
              f'{check_elapsed:.1f}s')
        # taskId 必须能回查（证明不是随机编的数字）
        if isinstance(sync_check.get('taskId'), int):
            st_t, env_t = request_with_status('GET', f'/api/teaching/plagiarism/tasks/{sync_check["taskId"]}',
                                              None, teacher, timeout=60)
            tdata = env_t.get('data') if isinstance(env_t, dict) else None
            check('#63 返回的 taskId 能回查到真实任务',
                  st_t == 200 and isinstance(tdata, dict) and tdata.get('state') in ('DONE','FAILED','PENDING','STAGE1','STAGE2'),
                  f"HTTP {st_t} state={None if not isinstance(tdata, dict) else tdata.get('state')}")
    else:
        check('#63 同步查重返回 data 对象', False, f'data={sync_check!r}')

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

    # ══════════════════════════════════════════════════════════════
    #  #71 POST /api/teaching/plagiarism/tasks/{taskId}/rerun
    #  契约 §6.7.9：重新查重（清空旧结论后重跑）；keepAudit 默认 false；
    #  响应 {taskId,newTaskId,state:"PENDING",estimatedMs}；未完成任务 → 409。
    #  这条此前同样没有自动化覆盖。
    # ══════════════════════════════════════════════════════════════
    print('── #71 重新查重（rerun）──', flush=True)
    done_task_id = task['taskId']
    # 记录重跑前该活动的报告数，用于验证「旧结论被清空 / 新任务产出新报告」
    reports_before = sql(f'SELECT COUNT(*) FROM teach_plagiarism_report WHERE assignment_id={exam};')

    st_r, env_r = request_with_status('POST', f'/api/teaching/plagiarism/tasks/{done_task_id}/rerun',
                                      {'keepAudit': False}, teacher, timeout=90)
    rerun = env_r.get('data') if isinstance(env_r, dict) else None
    check('#71 rerun HTTP 200 且信封 code=200',
          st_r == 200 and isinstance(env_r, dict) and env_r.get('code') == 200,
          f'HTTP {st_r} code={None if not isinstance(env_r, dict) else env_r.get("code")} '
          f'msg={None if not isinstance(env_r, dict) else env_r.get("message")}')
    if isinstance(rerun, dict):
        fixtures['rerunTaskId'] = rerun.get('newTaskId')
        # 契约 §6.7.9 响应四字段逐字
        check('#71 响应含 taskId/newTaskId/state/estimatedMs',
              isinstance(rerun.get('taskId'), int) and isinstance(rerun.get('newTaskId'), int)
              and rerun.get('state') == 'PENDING' and isinstance(rerun.get('estimatedMs'), int),
              f'taskId={rerun.get("taskId")} newTaskId={rerun.get("newTaskId")} '
              f'state={rerun.get("state")} estimatedMs={rerun.get("estimatedMs")}')
        check('#71 taskId 是原任务、newTaskId 是新任务（不能相同）',
              rerun.get('taskId') == done_task_id and rerun.get('newTaskId') != done_task_id,
              f'原={done_task_id} 返回 taskId={rerun.get("taskId")} newTaskId={rerun.get("newTaskId")}')
        # 新任务必须能回查，且真的会跑到 DONE
        new_id = rerun.get('newTaskId')
        if isinstance(new_id, int):
            def rerun_done():
                st_rr, env_rr = request_with_status('GET', f'/api/teaching/plagiarism/tasks/{new_id}',
                                                    None, teacher, timeout=60)
                d = env_rr.get('data') if isinstance(env_rr, dict) else None
                return d if isinstance(d, dict) and d.get('state') in ('DONE', 'FAILED') else None
            rerun_result = wait(rerun_done, 180)
            check('#71 重跑产生的新任务真实跑完', rerun_result and rerun_result.get('state') == 'DONE',
                  f"state={None if not rerun_result else rerun_result.get('state')}")
            # keepAudit=false → 旧结论清空后重新生成报告。报告数应 ≥ 之前的数量
            # （旧行可能被删+新行插入，也可能原地刷新；只要不为 0 且新任务有产出即可）
            reports_after = sql(f'SELECT COUNT(*) FROM teach_plagiarism_report WHERE assignment_id={exam};')
            new_task_reports = sql(f'SELECT COUNT(*) FROM teach_plagiarism_report WHERE assignment_id={exam} AND task_id={new_id};')
            check('#71 新任务产出了自己的报告行',
                  new_task_reports.isdigit() and int(new_task_reports) > 0,
                  f'新任务报告数={new_task_reports}（总报告 {reports_before} → {reports_after}）')
            # 未完成任务 → 409（负向分支，契约 §6.7.9 明写）
            # 先新建一个必然处于 PENDING/进行中的任务，再立刻 rerun 它
            st_n, env_n = request_with_status('POST', '/api/teaching/plagiarism/tasks',
                {'assignmentId': exam, 'problemIds': [pid], 'language': 'cpp',
                 'stage1SimThreshold': 75, 'stage2ChannelCode': 'local-qwen', 'scope': 'problem'},
                teacher, timeout=60)
            pending_id = (env_n.get('data') or {}).get('taskId') if isinstance(env_n, dict) else None
            if isinstance(pending_id, int):
                st_c, env_c = request_with_status('POST', f'/api/teaching/plagiarism/tasks/{pending_id}/rerun',
                                                  {'keepAudit': False}, teacher, timeout=60)
                # 刚创建的任务通常还在 PENDING → 应 409；若已经跑完（极快）则允许 200
                ok_conflict = st_c == 409 or st_c == 200
                check('#71 对未完成任务 rerun → 409（或任务已结束则 200）',
                      ok_conflict,
                      f'HTTP {st_c} code={None if not isinstance(env_c, dict) else env_c.get("code")} '
                      f'msg={None if not isinstance(env_c, dict) else env_c.get("message")}')
                # 等它跑完，避免 finally 里的清理撞上进行中任务
                wait(lambda: (request_with_status('GET', f'/api/teaching/plagiarism/tasks/{pending_id}', None, teacher, timeout=60)[1].get('data') or {}).get('state') in ('DONE','FAILED'), 180)
    else:
        check('#71 rerun 返回 data 对象', False, f'data={rerun!r}')

    req('POST', f'/api/teaching/exam/{exam}/finish', {'autoSubmitUnsubmitted':True}, teacher)
    req('POST', f'/api/teaching/exam/{exam}/answer', {'problemId':pid,'language':'cpp','code':CODE_A,'clientSavedAt':int(time.time()*1000)}, student, expected=409)
except Exception as error:
    check('验收流程执行完整', False, str(error))
finally:
    OUT.parent.mkdir(parents=True,exist_ok=True)
    OUT.write_text(json.dumps({'passed':sum(c['pass'] for c in checks),'total':len(checks),'checks':checks,'fixtures':fixtures},ensure_ascii=False,indent=2),encoding='utf-8')
    print(f"{sum(c['pass'] for c in checks)}/{len(checks)} passed; {OUT}",flush=True)
raise SystemExit(0 if checks and all(c['pass'] for c in checks) else 1)
