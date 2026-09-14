# LLM 노트북 운영자 체크리스트

이 문서는 LLM 서버 코드가 `main`에 병합된 뒤 저장소 소유자가 직접 해야 할
외부 설정과 Ubuntu 실기 검증을 순서대로 정리한다. 상세 명령과 장애 대응은
[`README.md`](./README.md)를 함께 참고한다.

## 현재 상태 — 2026-09-14 (실기 검증 완료, commit `6e60483`)

이 절은 2026-09-09에 작성된 뒤 파일 전용 Tunnel secret, 멱등한 2단계 Ubuntu
호스트 bootstrap, immutable release와 systemd 재부팅 복구 유닛, 응답 본문을
노출하지 않는 Cloudflare Access smoke, 강화된 단일 목적 배포 workflow가
추가되는 동안 갱신되지 않아 실제와 어긋나 있었다. 아래가 현재 상태다.

(`6e60483`는 이 문서 자신을 갱신하는 커밋의 부모다 — 커밋은 자기 자신의 SHA를
가리킬 수 없고, 그 커밋은 문서만 바꾸므로 아래 결과는 그 커밋이 만든 tree에도
그대로 적용된다.)

**2026-09-14: Ubuntu/NVIDIA 노트북 실기 검증을 실제로 수행해 통과했다.** 아래
목록에서 해당 항목이 `[x]`로 바뀌었다. Cloudflare/GitHub 외부 설정과 자동 배포,
백엔드 LLM 활성화는 여전히 수행되지 않았다.

- [x] 백엔드의 템플릿 우선 비동기 LLM 연동과 실패 시 전체 템플릿 폴백 구현
- [x] Ollama CPU/GPU, 모델 초기화, Cloudflare Tunnel Compose 구성 (파일 전용
      Tunnel secret 설계 포함)
- [x] macOS CPU에서 고정 이미지 빌드, `qwen3:4b-q8_0` 다운로드, 실제 smoke 완료
      (2026-09-09, macOS 개발 머신에서 1회성으로 수행 — Ubuntu 실기 검증이 아님)
- [x] `infra/llm`의 모든 shell 스크립트 shellcheck, `infra/llm/tests/`의 10개
      테스트 스위트, CPU/GPU/Tunnel 3단계 Compose 구조 검증(합성 토큰 파일 사용)을
      각각 연속 2회 실행해 두 번 모두 동일하게 통과 (오프라인, commit `06fd5f3`,
      2026-09-14 실행)
- [x] `toolchain-test.sh`, 프런트엔드 `ci`/`test`/`typecheck`/`build`, 백엔드
      `./gradlew --dependency-verification strict clean test`,
      `integration-e2e.sh`(PostgreSQL + 브라우저 여정, LLM 비활성),
      `actionlint`까지 hosted-동등 명령을 오프라인으로 전부 통과 (commit
      `06fd5f3`, 2026-09-14 실행)
- [x] Ubuntu/NVIDIA 노트북 실기 검증 완료 (2026-09-14). `nextvisit-runner`
      비밀번호 잠금 확인, `bootstrap-ubuntu-host.sh prepare`/`apply` 실행,
      `verify-host.sh gpu` 통과(`host verification passed for gpu mode`),
      고정 CUDA 컨테이너(`nvidia/cuda@sha256:c87e78933f4c16e3272123bf2f75537306596d0fbaa395a29696a22786e5ee0e`)에서
      GPU 가시성 확인, base+GPU Compose 기동, 모델 초기화(99초),
      loopback smoke 3회(**9초 / 5초 / 6초**, 모두 45초 기준 미만),
      `ollama ps`가 **`100% GPU`** 보고(컨텍스트는 아래 2026-09-15 항목대로 8192),
      `down`(`--volumes` 미사용) 후 재기동 시 모델 재다운로드 없음(6초),
      포트 11434는 `127.0.0.1` 전용이며 외부 리스너 없음.
      **재부팅 복구는 아직 검증하지 않았다**(6절).
- [ ] Cloudflare/GitHub 외부 설정 — Access 정책 구성, Tunnel 연결, 외부(EC2)
      Access smoke, GitHub runner group을 이 저장소와 `main` 배포 workflow로
      제한하는 작업 중 어느 것도 수행되지 않았다.
- [ ] 자동 배포 및 백엔드 LLM 활성화 — `LLM_DEPLOY_ENABLED`와
      `NEXTVISIT_LLM_ENABLED`는 계속 `false`이며, 둘 다 위 두 항목이 실기로
      전부 끝난 뒤에만 켠다.

이 오프라인 실행 전체의 명령과 결과는 위 항목에 요약돼 있다. 더 상세한 근거가
필요하면 이 커밋들의 이력과 각 커밋 메시지의 `Claude-Session` URL을 참고한다 —
검증 과정에서 쓰인 임시 작업 디렉터리는 저장소에 커밋되지 않는 scratch였다.

## 완료 조건

아래 항목을 위에서부터 순서대로 모두 완료해야 운영 준비가 끝난다.

1. 저장소가 GitHub 조직 소유이고 `llm-production` runner group이 정확한 저장소와
   `main` 배포 workflow에만 제한된다.
2. Ubuntu 노트북에서 `verify-host.sh gpu`가 통과하고 Ollama가 `100% GPU`로 실행된다.
3. Ollama에 호스트 공개 포트가 없고 Cloudflare Access를 거쳐서만 접근된다.
4. 재부팅 뒤 Docker 서비스, GitHub runner, Ollama, Tunnel이 복구된다.
5. 마지막에만 GitHub 배포와 백엔드 LLM을 차례로 활성화한다.

하나라도 충족하지 못하면 `LLM_DEPLOY_ENABLED`와
`NEXTVISIT_LLM_ENABLED`를 계속 `false`로 둔다.

## 0. 지금은 하지 않을 것

- [ ] 현재 개인 계정 저장소 `y-minion/wanted_Hackaton`에 repository-level
      self-hosted runner를 등록하지 않는다.
- [ ] `LLM_DEPLOY_ENABLED`를 만들지 않거나 `false`로 유지한다.
- [ ] Ubuntu 실기 검증 전에는 `NEXTVISIT_LLM_ENABLED=false`를 유지한다.
- [ ] Tunnel token, Access Client ID/Secret, GitHub runner 등록 token을 저장소,
      이슈, Actions 로그 또는 셸 기록에 남기지 않는다.
- [ ] 일상적인 배포와 종료에서 `docker compose down --volumes`를 사용하지 않는다.

GitHub는 self-hosted runner가 매 작업마다 초기화되지 않으며 운영자가 시스템을
관리해야 한다고 안내한다. 이 저장소의 workflow 검사는 보조 방어선일 뿐,
runner에 작업이 할당되기 전의 외부 접근 정책을 대신할 수 없다.

참고:

- [GitHub self-hosted runner 보안 안내](https://docs.github.com/en/actions/reference/security/secure-use)
- [GitHub runner group 접근 관리](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/manage-access)

## 1. GitHub 조직과 runner 경계 준비

- [ ] 비공개 저장소를 GitHub Organization으로 이전하거나 비공개 mirror를 만든다.
- [ ] 새 저장소의 기본 브랜치가 `main`이고 최신 코드가 반영됐는지 확인한다.
- [ ] `main`에 pull request review를 필수 조건으로 설정한다.
- [ ] `backend/**`, `infra/llm/**`, `.github/workflows/llm-*.yml`을 바꾼 PR에서는
      `LLM CI` 성공을 확인하고 병합한다.
- [ ] 현재 `LLM CI`에는 path filter가 있으므로 이를 그대로 모든 PR의 required
      status check로 등록하지 않는다. 그렇게 하면 관련 경로를 바꾸지 않은 PR도
      Pending 상태로 막힌다. 전역 필수 검사로 만들려면 먼저 workflow를 항상
      실행하고 관련 없는 job만 성공 처리하도록 별도 변경한다.
- [ ] Organization **Settings → Actions → Runner groups**에서
      `llm-production` 그룹을 만든다.
- [ ] Repository access를 **Selected repositories**로 두고 대상 저장소 하나만 고른다.
- [ ] Workflow access를 제한하고 아래 workflow 하나만 허용한다.

```text
<ORG>/<REPO>/.github/workflows/llm-deploy.yml@refs/heads/main
```

- [ ] API나 관리 화면에서 다음 값이 모두 일치하는지 다시 확인한다.

```text
name = llm-production
visibility = selected
restricted_to_workflows = true
selected_workflows = [<ORG>/<REPO>/.github/workflows/llm-deploy.yml@refs/heads/main]
```

통과 기준: 대상 저장소 하나와 위 workflow 하나만 group을 사용할 수 있어야 한다.
현재 GitHub 플랜에서 workflow 단위 제한을 제공하지 않으면 runner를 등록하지 말고
배포를 비활성 상태로 유지한다.

참고: [GitHub의 skipped required check 안내](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks)

## 2. Ubuntu 노트북 준비

- [ ] Ubuntu Server 24.04 x86_64와 보안 업데이트를 설치한다.
- [ ] 노트북 GPU에 맞는 NVIDIA 권장 드라이버를 설치하고 `nvidia-smi`가 정상
      동작하는지 확인한다. 아래 bootstrap은 드라이버 패키지를 절대 설치·변경하지
      않으며, `nvidia-smi`가 동작하지 않으면 실행을 거부한다.
- [ ] `curl`, `jq`, `git`, `openssh-client`를 설치한다.
- [ ] Docker 데이터 영역에 최소 20 GiB 여유 공간을 확보한다.
- [ ] 유선 네트워크와 전원(AC)을 연결한다.
- [ ] 공유기 포트포워딩이나 공인 inbound 포트를 만들지 않는다.

Docker Engine, Compose plugin, NVIDIA Container Toolkit, 전용 계정, 보호 디렉터리,
서버용 전원 설정은 두 단계 bootstrap이 멱등하게 처리한다. `prepare`는 아무 패키지도
설치하지 않고, 공식 서명 저장소 키·목록 파일만 쓴 뒤 해결된 후보 버전과 그 파일들의
SHA-256을 비밀이 아닌 `/etc/nextvisit/ubuntu-packages.lock`(root 소유, `0444`)에
기록한다.

```bash
sudo infra/llm/scripts/bootstrap-ubuntu-host.sh prepare
```

- [ ] 출력된 `package name=version` 목록이 설치하려는 버전인지 검토한다.
- [ ] 출력된 `ubuntu-packages.lock sha256:` 값을 그대로 `apply`에 전달한다.

```bash
sudo infra/llm/scripts/bootstrap-ubuntu-host.sh apply <ubuntu-packages.lock sha256>
```

`apply`는 SHA-256이 다르거나 없으면 어떤 패키지도 건드리기 전에 중단하고, 후보
버전을 다시 해결해 `prepare` 이후의 drift를 거부한 뒤 정확히 `package=version`
인자로만 설치한다. 배포판 전체 업그레이드는 절대 실행하지 않는다. 이어서
`nextvisit-cloudflared`(GID `65532`) 시스템 그룹, 사용 가능한 비밀번호가 없는
`nextvisit-runner` 계정(홈 `/home/nextvisit-runner`, 셸 `/bin/bash`, 그룹
`docker,nextvisit-cloudflared`), `nextvisit-runner` 전용
`/opt/nextvisit/llm`과 `/opt/nextvisit/llm/releases`(둘 다 `0750` —
`current`를 원자적으로 바꾸려면 `nextvisit-runner`가 상위 디렉터리 자체에도
쓰기 권한이 있어야 한다), `root:65532 0750`인 `/etc/nextvisit`을
만들고, Docker NVIDIA runtime 구성과
`/etc/systemd/logind.conf.d/10-nextvisit-llm.conf` drop-in 작성,
sleep/suspend/hibernate/hybrid-sleep target mask까지 수행한다. 스크립트는
재부팅하지 않는다.

- [ ] `apply` 마지막 줄이 `ubuntu host bootstrap apply completed`인지 확인한다.
- [ ] 출력된 `installed name=version` 값을 아래 표에 기록한다.

```text
installed containerd.io=
installed docker-ce=
installed docker-ce-cli=
installed docker-compose-plugin=
installed libnvidia-container1=
installed nvidia-container-toolkit=
```

- [ ] 기록을 마친 뒤 직접 재부팅한다.

`nextvisit-cloudflared`(GID `65532`)는 3절에서 만드는 `/etc/nextvisit/llm.token`을
`nextvisit-runner`가 `sudo` 없이도 읽을 수 있게 하며, 아래 파일·디렉터리를 만들
때 쓰는 숫자 그룹과 반드시 같아야 한다. `apply`는 이 token 파일을 만들지도,
덮어쓰지도 않는다. 이미 있으면 바이트 단위로 그대로 둔다.

설치 버전과 명령의 근거는 실행 시점의 공식 문서를 따른다.

- [Docker Engine on Ubuntu](https://docs.docker.com/engine/install/ubuntu/)
- [NVIDIA Container Toolkit 설치](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html)

재부팅 뒤 비공개 저장소에 쓰기 권한이 없는 전용 deploy key를 만든다.

```bash
(
set -eu
key_path=/home/nextvisit-runner/.ssh/nextvisit-readonly
sudo install -d -o nextvisit-runner -g nextvisit-runner -m 0700 /home/nextvisit-runner/.ssh
sudo -iu nextvisit-runner ssh-keygen -q -t ed25519 -N '' -f "$key_path"
sudo -iu nextvisit-runner sed -n '1p' "$key_path.pub"
)
```

- [ ] 마지막에 출력된 **공개 키만** 조직 저장소의 **Settings → Deploy keys**에
      추가하고 **Allow write access**는 선택하지 않는다.
- [ ] [GitHub 공식 SSH host key fingerprint](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/githubs-ssh-key-fingerprints)와
      대조해 `nextvisit-runner`의 `known_hosts`를 준비한다.
- [ ] 아래 `repository_url`을 조직 저장소의 실제 SSH clone URL로 바꾼다.

저장소를 정확한 경로에 clone하고 `main` revision을 기록한다. placeholder URL이
남아 있으면 명령은 실패하도록 닫혀 있다.

```bash
sudo -iu nextvisit-runner bash <<'RUNNER'
set -eu
repository_url=git@github.com:YOUR_ORG/YOUR_REPO.git
repo_dir=/home/nextvisit-runner/wanted_Hackaton
case "$repository_url" in
  *YOUR_ORG*|*YOUR_REPO*) exit 1 ;;
esac
GIT_SSH_COMMAND='ssh -i /home/nextvisit-runner/.ssh/nextvisit-readonly -o IdentitiesOnly=yes' \
  git clone --branch main --single-branch "$repository_url" "$repo_dir"
cd "$repo_dir" || exit 1
git config core.sshCommand \
  'ssh -i /home/nextvisit-runner/.ssh/nextvisit-readonly -o IdentitiesOnly=yes'
test "$(git branch --show-current)" = main
git remote get-url origin
git rev-parse HEAD
RUNNER
```

- [ ] 출력된 origin이 대상 조직 저장소인지 확인한다.
- [ ] 출력된 HEAD가 배포하려는 `main` commit과 같은지 운영 기록에 남긴다.

이후 노트북 명령은 같은 `nextvisit-runner` 계정으로 실행한다.

```bash
sudo -iu nextvisit-runner bash <<'RUNNER'
set -eu
repo_dir=/home/nextvisit-runner/wanted_Hackaton
cd "$repo_dir/infra/llm" || exit 1
./scripts/verify-host.sh gpu
RUNNER
```

GPU 모드는 Docker/Compose·디스크·`nvidia-smi`·`nvidia-ctk`·Docker nvidia runtime에
더해 `nextvisit-runner`의 잠긴 비밀번호 상태, `/opt/nextvisit/llm`과
`/opt/nextvisit/llm/releases` 소유와 권한, 절전 target mask, `11434` 공개
listener 부재까지 확인한다. `nextvisit-runner`는
자기 자신의 비밀번호 상태를 `sudo` 없이 읽을 수 있으므로 이 명령도 그 계정으로 실행
하며, 이렇게 해야 이 저장소의 배포 워크플로가 실제로 그 계정으로 Docker 소켓에
접근할 수 있는지까지 검증된다. Tunnel token은 요구하지 않으므로 3절보다 먼저 통과
해야 한다.

- [ ] 마지막 줄이 `host verification passed for gpu mode`인지 확인한다.
- [ ] `docker compose version --short`가 `2.24.4` 이상인지 확인한다.
- [ ] `nvidia-smi`가 GPU와 드라이버를 정상 표시하는지 확인한다.

Docker 그룹은 사실상 root 권한이므로 `nextvisit-runner`를 일반 사용자 작업에
사용하지 않는다.

재부팅 복구용 systemd 유닛을 설치한다. 이 유닛은 `/opt/nextvisit/llm/current`가
가리키는 release만 사용하며, `nextvisit-runner`로만 실행되고 token 값이 아닌
파일 경로만 다룬다. `/opt/nextvisit/llm`이 `nextvisit-runner:nextvisit-runner
0750`이므로 release를 만들고 `current`를 바꾸는 작업 자체는 `sudo` 없이
`nextvisit-runner`로 실행한다. 5절에서 자동 배포를 활성화하면 이후에는
`llm-deploy.yml`의 `Stage immutable release` 단계가 매 배포마다 같은 일을
하지만, 여기 2절 시점에는 아직 그 workflow가 실행된 적이 없으므로 최초
release는 직접 한 번 만들어 둔다. 유닛 파일을 `/etc/systemd/system/`에 설치하고
enable하는 부분만 `root` 권한이 필요하다.

이 시점에는 유닛을 설치하고 enable만 해 둔다. `ExecStartPre`가 요구하는
`/etc/nextvisit/llm.token`은 3절에서야 만들어지고, `ExecStart`가 기대하는
빌드된 이미지와 pull된 모델도 4절에서야 준비되므로, 지금 시작하면
`ExecStartPre`가 즉시 거부하거나(token 없음) `TimeoutStartSec=600` 안에
이미지 빌드와 모델 pull, Tunnel 연결까지 다 끝내야 하는 상태로 반드시
실패한다. 실제 `systemctl start`와 `is-enabled`/`is-active` 확인은 token·
이미지·모델이 모두 준비되고 Tunnel/Access 검증까지 끝난 뒤인 4절 끝에서
한다.

```bash
sudo -iu nextvisit-runner bash <<'RUNNER'
set -eu
repo_dir=/home/nextvisit-runner/wanted_Hackaton
sha="$(git -C "$repo_dir" rev-parse HEAD)"
"$repo_dir/infra/llm/scripts/stage-runtime.sh" "$sha" "$repo_dir"
RUNNER
```

`root`가 지금 설치하는 유닛 파일의 바이트는 `nextvisit-runner`가 쓸 수 있는
저장소 checkout(`$repo_dir`)에서 그대로 온다. 오늘은 `nextvisit-runner`가
이미 docker 그룹(이 호스트에서 사실상 root와 동급)이라 새로운 권한 상승이
아니지만, 이 계정이 나중에 rootless Docker나 socket proxy로 재구성돼
docker-root 동급성을 잃으면 이 설치 스텝은 조용히 상승 경로가 된다. 그때는
`sudo install` 전에 `nextvisit-runner`가 쓸 수 없는 위치(`/root` 등)로 복사한
뒤 `diff`로 내용을 확인하고 나서 설치하도록 바꿔야 한다.

```bash
(
set -eu
repo_dir=/home/nextvisit-runner/wanted_Hackaton
sudo install -o root -g root -m 0644 \
  "$repo_dir/infra/llm/systemd/nextvisit-llm.service" \
  /etc/systemd/system/nextvisit-llm.service
sudo systemctl daemon-reload
sudo systemctl enable nextvisit-llm.service
)
```

`stage-runtime.sh`는 인자를 넘기지 않으면 기본값
`/opt/nextvisit/llm/releases`와 `/opt/nextvisit/llm/current`를 그대로
사용하므로 별도 환경변수가 필요 없다.

- [ ] `/opt/nextvisit/llm/current`가 방금 만든 release SHA를 가리키는지
      `readlink /opt/nextvisit/llm/current`로 확인한다.

`systemctl start`와 `is-enabled`/`is-active` 확인은 아직 하지 않는다. 3절에서
token을 만들고 4절에서 이미지·모델·Tunnel·Access를 모두 검증한 뒤, 4절 끝에서
이 유닛을 시작한다.

5절에서 자동 배포가 활성화된 뒤에는 매 `main` 배포가
`stage-runtime.sh "$GITHUB_SHA" "$GITHUB_WORKSPACE"`를 실행해 새 release를
만들고 `current`를 그쪽으로 옮긴다. 이 유닛은 그 `current`만 바라보므로,
배포와 재부팅 복구가 항상 같은 release를 가리킨다.

### 2026-09-15 LLM 실기 검증과 고정값 변경

노트북에서 API·PostgreSQL·Ollama를 함께 띄우고 `NEXTVISIT_LLM_ENABLED=true`로
질문 생성 경로를 처음 끝까지 돌렸다. 결과: `code=SUCCESS`, `attempts=2`,
`elapsedMs=42962`, 준비 카드의 세 질문이 모두 `source=LLM`이며 허용된 표면
변환(`"습니다. "`→`"는데 "`, `"입니다. "`→`"인데 "`)만 적용됐다.

그 과정에서 원래 고정값으로는 **이 기능이 전혀 동작하지 않는다**는 것이
드러나 세 값을 바꿨다.

| 값 | 이전 | 이후 | 근거 |
| --- | --- | --- | --- |
| `OLLAMA_CONTEXT_LENGTH` | 2048 | 8192 | qwen3는 하이브리드 추론 모델이라 이 프롬프트에 ~2500 토큰을 생각에 쓴다. 2048에서는 생각을 끝내지 못한 채 잘려 `finish_reason=length`, `content` 빈 문자열, 즉 항상 `EMPTY_CONTENT`였다. |
| 모델 양자화 | `q8_0` | `q4_K_M` | q8로 컨텍스트를 8192로 키우면 KV 캐시가 6 GiB VRAM을 넘쳐 `10%/90% CPU/GPU`로 유출되고 57초가 걸렸다. q4는 4.0 GB로 **`100% GPU`를 유지하며 26~28초**. |
| `NEXTVISIT_LLM_MAX_OUTPUT_TOKENS` | 512 | 3000 | 추론 토큰까지 담아야 답이 나온다. |

`/no_think`는 효과가 없었다(system·user 어느 쪽에 두어도 추론이 계속됐고,
user 쪽에서는 오히려 늘었다). Ollama의 OpenAI 호환 엔드포인트는 `think:false`
파라미터를 무시한다. 두 경우 모두 실측으로 확인했다.

검증기 규칙(`QuestionOutputGuard`)을 그대로 계산해 만든 기대값과 모델 출력을
3회 대조해 세 문장 모두 정확히 일치함을 확인했다. 이 호출은 비동기이므로
26~45초 지연은 보호자 대기 시간이 아니다 — 저장 즉시 템플릿 질문이 나오고
LLM 결과는 뒤에 반영된다.

### 2026-09-14 실기 설치 기록 (비밀값 없음)

호스트: Ubuntu 24.04, x86_64, 커널 6.8.0-139, Intel i7-7700HQ, RAM 23.2 GiB,
GPU **GeForce GTX 1060 6144 MiB / 드라이버 580.173.02**(bootstrap이 건드리지 않음),
AC 전원 연결, 시간 동기 정상, Secure Boot 비활성.

`apply`가 설치한 정확한 버전:

```text
containerd.io=2.3.5-1~ubuntu.24.04~noble
docker-buildx-plugin=0.37.1-1~ubuntu.24.04~noble
docker-ce-cli=5:29.8.0-1~ubuntu.24.04~noble
docker-ce=5:29.8.0-1~ubuntu.24.04~noble
docker-compose-plugin=5.5.1-1~ubuntu.24.04~noble
libnvidia-container1=1.20.0-1
nvidia-container-toolkit=1.20.0-1
```

승인에 사용한 lock SHA-256: `2afe9ed30156d025966f3f67cd865afb486c2366e10f0ba7c431f2e1e3fa5a79`

서명 저장소 지문(두 번의 `prepare`에서 동일했고, 공식 출처와 대조해 일치 확인):

```text
/etc/apt/keyrings/docker.asc                          sha256:1500c1f56fa9e26b9b8f42452a553675796ade0807cdce11975eb98170b3a570
/etc/apt/sources.list.d/docker.list                   sha256:2e87eb934ec45a4f64c6b0570da3faf4073267498fcc73eaa28ed8938718a3e3
/etc/apt/sources.list.d/nvidia-container-toolkit.list sha256:d6229affd0edd66579f7dd75dfd09d31a32aba77dbbc7ed7e154fdb29bc3aab9
/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg sha256:425822bb25bfa7f5ce96e598a7bbd27db128649e4113017b3ff765b98b43b166
```

두 `apply` 모두 `0 upgraded, ... 0 to remove and 49 not upgraded`로 끝나 배포판
전체 업그레이드가 없었음을 보였다. 2차 `apply`는 `nvidia-ctk`를 다시 실행하지도
Docker를 재시작하지도 않아 멱등성이 실기에서 확인됐다.

적용된 호스트 상태: `/etc/nextvisit` = `root:nextvisit-cloudflared 0750`,
`/opt/nextvisit/llm`과 `releases/` = `nextvisit-runner:nextvisit-runner 0750`,
`/opt/nextvisit` = `root:root 0755`(변경 없음), sleep/suspend/hibernate/
hybrid-sleep 타깃 4개 모두 `masked`, `nextvisit-runner`의 그룹은
`nextvisit-runner docker nextvisit-cloudflared`.

주의: `docker-buildx-plugin`은 처음 6개 목록에 빠져 있었다. `infra/llm/Dockerfile`이
`COPY --chmod=`을 쓰는데 이는 BuildKit 전용이라, buildx 없이는
`the --chmod option requires BuildKit`으로 빌드가 실패한다. 첫 실기 빌드에서
드러나 목록에 추가했다.

## 3. Cloudflare Tunnel과 비밀값 준비

- [ ] Cloudflare Zero Trust에서 remotely-managed Tunnel을 만든다.
- [ ] public hostname의 origin service를 정확히 `http://ollama:11434`로 설정한다.
- [ ] 같은 hostname에 self-hosted Access application을 만든다.
- [ ] 백엔드 전용 Service Token을 만든다.
- [ ] Access 정책을 **Action: Service Auth → Include: Service Token → 방금 만든
      백엔드 token 하나**로 설정한다. 다른 Include 또는 Bypass 규칙을 두지 않는다.

참고:

- [Cloudflare Tunnel routing](https://developers.cloudflare.com/tunnel/routing/)
- [Cloudflare Tunnel token](https://developers.cloudflare.com/tunnel/advanced/tunnel-tokens/)
- [Cloudflare Access Service Token](https://developers.cloudflare.com/cloudflare-one/access-controls/service-credentials/service-tokens/)

통과 기준: 이 hostname의 Access 정책에는 백엔드 전용 token 하나만 포함되고,
다른 사용자·그룹·Service Token을 허용하거나 Access를 우회하는 규칙이 없어야 한다.

노트북에는 Tunnel token만, 환경변수가 아닌 Compose secret 파일 mount로 저장한다.

```bash
(
set -eu
sudo install -d -o root -g 65532 -m 0750 /etc/nextvisit
sudo install -o root -g 65532 -m 0440 /dev/null /etc/nextvisit/llm.token
sudoedit /etc/nextvisit/llm.token
repo_dir=/home/nextvisit-runner/wanted_Hackaton
sudo "$repo_dir/infra/llm/scripts/verify-tunnel-token-file.sh" /etc/nextvisit/llm.token
)
```

`/etc/nextvisit/llm.token`에는 `TUNNEL_TOKEN=` 접두사 없이 Cloudflare가 발급한
원본 token 값 한 줄만, LF 한 개로 끝나도록 저장한다. group `65532`는 원래
cloudflared 컨테이너의 non-root 실행 사용자 번호이며, 2절에서 만든
`nextvisit-cloudflared` 그룹이 같은 GID를 재사용해 `nextvisit-runner`에게도
읽기 권한을 준다.

- [ ] 위 검사 스크립트가 `tunnel token file verified`만 출력하는지 확인한다.
- [ ] Access Client ID/Secret이 이 파일에 들어 있지 않은지 확인한다.

백엔드 서버의 secret store에는 다음 값을 별도로 저장하되 아직 활성화하지 않는다.

```dotenv
NEXTVISIT_LLM_ENABLED=false
NEXTVISIT_LLM_BASE_URL=https://llm.example.com/v1
NEXTVISIT_LLM_MODEL=qwen3:4b-q4_K_M
NEXTVISIT_LLM_API_KEY=ollama
NEXTVISIT_LLM_CF_ACCESS_CLIENT_ID=<ACCESS_CLIENT_ID>
NEXTVISIT_LLM_CF_ACCESS_CLIENT_SECRET=<ACCESS_CLIENT_SECRET>
```

## 4. Ubuntu 실기 검증

먼저 loopback 포트에서 이미지, 모델, OpenAI 호환 응답을 확인한다.

```bash
sudo -iu nextvisit-runner bash <<'RUNNER'
set -eu
repo_dir=/home/nextvisit-runner/wanted_Hackaton
cd "$repo_dir/infra/llm" || exit 1
export NEXTVISIT_LLM_TOKEN_FILE=/etc/nextvisit/llm.token
docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml up --detach --wait --build ollama
docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml run --rm model-init
attempt=1
while [ "$attempt" -le 3 ]; do
  started_at="$(date +%s)"
  ./scripts/smoke-openai.sh http://127.0.0.1:11434/v1
  elapsed_seconds="$(($(date +%s) - started_at))"
  test "$elapsed_seconds" -lt 45
  printf 'smoke attempt %s: %ss\n' "$attempt" "$elapsed_seconds"
  attempt="$((attempt + 1))"
done
ollama_id="$(docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml ps -q ollama)"
docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$ollama_id" | grep -Fx 'OLLAMA_CONTEXT_LENGTH=8192'
docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml exec ollama ollama list | grep -F 'qwen3:4b-q4_K_M'
docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml exec ollama ollama ps
RUNNER
```

- [ ] smoke의 마지막 줄이 `OpenAI-compatible smoke passed`인지 확인한다.
- [ ] 환경 검사와 모델 목록이 `qwen3:4b-q4_K_M`, context `8192`를 확인하는지 본다.
- [ ] `ollama ps`에서 모델이 `100% GPU`인지 확인한다.
- [ ] 위 loop가 3회 모두 성공하고 각 요청이 백엔드 기본 read timeout인
      45초보다 짧은지 기록한다.

이제 최종 Tunnel 구성을 적용한다. 이 명령은 Ollama의 loopback 공개 포트도 제거한다.

```bash
sudo -iu nextvisit-runner bash <<'RUNNER'
set -eu
repo_dir=/home/nextvisit-runner/wanted_Hackaton
cd "$repo_dir/infra/llm" || exit 1
export NEXTVISIT_LLM_TOKEN_FILE=/etc/nextvisit/llm.token
docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml up --detach --wait ollama cloudflared
ollama_id="$(docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml ps -q ollama)"
test -n "$ollama_id"
test "$(docker inspect -f '{{len .HostConfig.PortBindings}}' "$ollama_id")" = 0
RUNNER
```

- [ ] healthcheck가 있는 `ollama`는 healthy, `cloudflared`는 running인지 확인한다.
- [ ] `PortBindings` 검사가 성공하고 호스트의 `11434` 포트가 공개되지 않는지 확인한다.

백엔드 EC2에서, AWS SSM으로 이 저장소의 release bundle에 포함된
`infra/llm/scripts/smoke-access.sh`를 실행한다. Client ID/Secret 값은 절대
환경변수나 명령줄 인자로 넘기지 않고, secret store가 그 값들을 구체화해 둔
파일 경로 두 개만 인자로 전달한다. 각 파일은 일반 파일(symlink 아님)이어야
하고 권한은 `0440`/`0400`/`0600` 중 하나, 값은 한 줄, header-safe ASCII여야
한다 — 하나라도 어긋나면 스크립트는 값 대신 고정 문구만 출력하고 거부한다.
이 스크립트는 노트북이 아니라 오직 EC2에서만 외부 인증에 쓰인다.

```bash
infra/llm/scripts/smoke-access.sh \
  "$NEXTVISIT_LLM_BASE_URL" \
  /etc/nextvisit/access-client-id \
  /etc/nextvisit/access-client-secret
```

응답 본문과 자격증명은 권한이 제한된 임시 파일로만 다뤄지며 화면에 출력되지
않는다. 인증 요청이 HTTP 200에 [`smoke-openai.sh`](./scripts/smoke-openai.sh)와
동일한 질문 1개 봉투를 반환하고 무인증 요청이 302/401/403 중 하나로 거부될
때만 스크립트는 고정 문구 `Access smoke passed`를 출력하고 종료코드 0을
반환한다.

- [ ] 고정 성공 문구 `Access smoke passed`만 출력되고 응답 본문·자격증명은
      출력되지 않는지 확인한다.
- [ ] 인증된 요청 직후 노트북에서 아래 명령으로 다시 `100% GPU`를 확인한다.

```bash
sudo -iu nextvisit-runner bash <<'RUNNER'
set -eu
repo_dir=/home/nextvisit-runner/wanted_Hackaton
cd "$repo_dir/infra/llm" || exit 1
export NEXTVISIT_LLM_TOKEN_FILE=/etc/nextvisit/llm.token
docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml exec ollama ollama ps
RUNNER
```

- [ ] 실측 시간, GPU 사용 상태, 검증 일시를 운영 기록에 남기되 요청·응답 본문과
      자격증명은 기록하지 않는다.

token(3절)과 이미지·모델(위 loopback 검증), Tunnel/Access(위 두 블록)가 모두
준비되고 확인됐으므로, 이제 2절에서 설치·enable만 해 둔 재부팅 복구 유닛을
시작한다.

```bash
sudo systemctl start nextvisit-llm.service
```

- [ ] `systemctl is-enabled nextvisit-llm.service`가 `enabled`인지 확인한다.
- [ ] `systemctl is-active nextvisit-llm.service`가 `active`인지 확인한다.

## 5. GitHub runner 등록

이 단계는 1절의 조직 제한이 먼저 검증된 경우에만 진행한다. 아직 자동 배포를
활성화하지 않는다.

- [ ] Organization의 `llm-production` group 화면에서 **New self-hosted runner**를 선택한다.
- [ ] 먼저 `nextvisit-runner` 전용 셸을 연다.

```bash
sudo -iu nextvisit-runner
```

- [ ] 새 프롬프트로 바뀐 것을 확인한 뒤 history를 끈다.

```bash
set +o history
```

- [ ] 그 셸에서 GitHub가 방금 생성한 다운로드·config 명령을 한 번만 실행하고
      즉시 `exit`한다.
- [ ] 등록 token이나 생성 명령을 파일, 메신저, 이슈에 복사하지 않았는지 확인한다.
- [ ] runner를 `llm-production` group에 넣고 기본 `self-hosted`, `linux`와 사용자
      label `llm`을 모두 유지한다.
- [ ] runner 설치 폴더에서 서비스를 설치하고 시작한다.

```bash
(
set -eu
runner_dir=/home/nextvisit-runner/actions-runner
cd "$runner_dir" || exit 1
sudo ./svc.sh install nextvisit-runner
sudo ./svc.sh start
sudo ./svc.sh status
)
```

- [ ] GitHub environment `llm-laptop`을 만들고 가능한 플랜에서는 required reviewer를 둔다.
- [ ] Organization 화면에서 runner가 Idle이고 group이 `llm-production`인지 확인한다.
- [ ] `LLM_DEPLOY_ENABLED`가 여전히 없거나 `false`인지 확인한다.

## 6. 재부팅 복구 검증

- [ ] 노트북을 재부팅한다.
- [ ] `docker`와 GitHub runner service가 자동 시작됐는지 확인한다.
- [ ] `systemctl status nextvisit-llm.service`가 `active (exited)`인지 확인한다.
      `nextvisit-llm.service`는 2절에서 설치한 `/opt/nextvisit/llm/current`
      release만 사용해 `ollama`를 healthy로 올리고, `model-init`을 멱등하게
      실행한 뒤 `cloudflared`를 마지막에 시작한다.
- [ ] `ollama`는 다시 healthy, `cloudflared`는 다시 running인지 확인한다.
- [ ] `nextvisit-llm-ollama-data` volume이 유지되고 모델을 다시 다운로드하지 않는지 확인한다.
- [ ] Tunnel hostname이 Access 인증 chat completion에 다시 응답하는지 확인한다.
- [ ] 인증된 요청 직후 `ollama ps`에서 다시 `100% GPU`를 확인한다.
- [ ] GitHub 화면에서 runner가 다시 Idle 상태인지 확인한다.

통과 기준: 수동 서비스 시작, 재설치 또는 모델 재다운로드 없이 재부팅 후
Access 인증 요청이 성공해야 한다.

## 7. 자동 배포 활성화

- [ ] 1~6절의 증거를 검토하고 미완료 항목이 없는지 확인한다.
- [ ] Repository variable `LLM_DEPLOY_ENABLED=true`를 마지막에 설정한다.
- [ ] `Deploy LLM Laptop` workflow를 `main`에서 수동 실행한다.
- [ ] 모든 단계가 성공하고 최종 구성에서 Ollama host port가 없음을 Actions 결과로 확인한다.
- [ ] workflow 성공은 로컬 stack 적용 성공일 뿐 Tunnel/Access 성공을 뜻하지 않는다.
      직후 4절의 Access 인증·무인증 smoke와 `100% GPU` 검사를 다시 실행한다.

통과 기준: PR용 `LLM CI`는 GitHub-hosted `ubuntu-latest`에서 실행되고,
노트북 runner에는 `main`의 `llm-deploy.yml` deploy job만 배정돼야 한다.
또한 매 배포 뒤 외부 Access smoke까지 성공해야 해당 배포를 완료로 기록한다.

## 8. 백엔드 LLM 활성화

자동 배포와 외부 연결 검증이 모두 끝난 뒤에만 진행한다.

- [ ] 백엔드 secret store의 `NEXTVISIT_LLM_BASE_URL`, 모델명, Access Client ID/Secret을 확인한다.
- [ ] `NEXTVISIT_LLM_ENABLED=true`로 바꾸고 API를 재시작한다.
- [ ] 테스트 케이스에서 새 주간 기록을 저장한다.
- [ ] `question_cache.status`가 `LLM_PENDING`에서 `LLM_DONE`으로 바뀌는지 확인한다.
- [ ] 준비 카드가 계속 최대 3개 질문만 반환하고 금지 표현·추가 사실이 없는지 확인한다.
- [ ] 노트북이나 Tunnel을 잠시 중단했을 때 API 요청은 성공하고 안전한 템플릿 질문이
      유지되는지 확인한다.

통과 기준: LLM 성공 시 허용된 문장만 나오고, LLM 실패 시에도 사용자 요청과
규칙 기반 템플릿이 정상 동작해야 한다.

## 9. 롤백

일반 장애라면 아래 순서로 기능을 축소한다.

1. Repository variable `LLM_DEPLOY_ENABLED=false`로 새 자동 배포의 진입을 먼저 막는다.
2. GitHub Actions에서 실행 중이거나 대기 중인 `Deploy LLM Laptop` run을 모두
   취소하고, 남은 run이 없는지 다시 확인한다.
3. 백엔드의 `NEXTVISIT_LLM_ENABLED=false`로 바꾸고 API를 재시작한다.
4. 필요하면 노트북에서 아래 명령으로 최종 stack을 내린다. named volume을
   보존하기 위해 `--volumes`는 붙이지 않는다.

```bash
sudo -iu nextvisit-runner bash <<'RUNNER'
set -eu
repo_dir=/home/nextvisit-runner/wanted_Hackaton
cd "$repo_dir/infra/llm" || exit 1
export NEXTVISIT_LLM_TOKEN_FILE=/etc/nextvisit/llm.token
docker compose --project-name nextvisit-llm \
  -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml down
RUNNER
```

자격증명 노출이 의심되면 위 순서를 기다리지 않는다.

- Access Service Token이 의심되면 Cloudflare에서 해당 token을 즉시 삭제해
  폐기한 다음 새 token을 발급한다. Access 정책의 유일한 Include 대상을 새
  token으로 교체하고, 백엔드 secret store의
  `NEXTVISIT_LLM_CF_ACCESS_CLIENT_ID`와
  `NEXTVISIT_LLM_CF_ACCESS_CLIENT_SECRET`도 새 값으로 교체한다.
- Tunnel token이 의심되면 즉시 회전하고 **기존 Tunnel 연결을 전부 강제 종료**한다.
  token 회전만으로는 이미 연결된 connector가 끊기지 않는다. Cloudflare의
  [compromised token 절차](https://developers.cloudflare.com/tunnel/advanced/tunnel-tokens/#rotate-a-compromised-token)에 따라 연결이 모두 종료됐는지 확인한다.
- 새 Tunnel token으로 `/etc/nextvisit/llm.token`을 교체하고
  `verify-tunnel-token-file.sh /etc/nextvisit/llm.token`으로 다시 확인한 뒤
  아래 명령으로 정상 `cloudflared`만 다시 만든다.

```bash
sudo -iu nextvisit-runner bash <<'RUNNER'
set -eu
repo_dir=/home/nextvisit-runner/wanted_Hackaton
cd "$repo_dir/infra/llm" || exit 1
export NEXTVISIT_LLM_TOKEN_FILE=/etc/nextvisit/llm.token
docker compose --project-name nextvisit-llm \
  -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml \
  up --detach --wait --force-recreate cloudflared
RUNNER
```

그 다음 1~4번 일반 롤백 절차로 자동 배포와 백엔드 LLM을 비활성화한다.
백엔드는 `NEXTVISIT_LLM_ENABLED=false`인 상태로 재시작해 새 Access 자격증명을
읽게 한다. 4절의 인증·무인증 Access smoke를 다시 통과하기 전에는 어느 기능도
재활성화하지 않는다.

백엔드 LLM을 꺼도 규칙 엔진과 템플릿 질문은 계속 동작한다. 롤백 뒤에는 원인,
발생 시각, 고정 오류 코드만 기록하고 보호자 입력·프롬프트·모델 응답·자격증명을
로그나 이슈에 붙이지 않는다.
