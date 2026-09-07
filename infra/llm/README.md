# NextVisit LLM operations

The required image is Ollama `0.33.3`; Docker Compose v2.24.4 or later is
required. The default model is `qwen3:4b-q8_0`
with a 2,048-token context. The named volume `nextvisit-llm-ollama-data`
survives container replacement.

## macOS CPU development

```bash
cp .env.example .env
docker compose -f compose.yml up --detach --wait --build ollama
docker compose -f compose.yml run --rm model-init
./scripts/smoke-openai.sh http://127.0.0.1:11434/v1
docker compose -f compose.yml down
```

`model-init` downloads about 4.4 GB only when the configured tag is missing.
Omit that command when checking container health without downloading the model.
Never use `docker compose down --volumes` for routine deployment.

## Ubuntu laptop preparation — deferred until hardware is ready

1. Install Ubuntu Server, the NVIDIA driver recommended for the laptop,
   Docker Engine with the Compose plugin, and NVIDIA Container Toolkit.
2. Create the dedicated runner account, grant its required Docker access,
   configure Docker's NVIDIA runtime, and reboot:

   ```bash
   sudo useradd --create-home --shell /bin/bash nextvisit-runner
   sudo usermod --append --groups docker nextvisit-runner
   sudo nvidia-ctk runtime configure --runtime=docker
   sudo systemctl restart docker
   sudo reboot
   ```

3. The repository-scoped Actions runner checks out each triggering commit with
   its ephemeral `contents: read` token and needs no deploy-capable Git
   credential. For manual checks only, use a read-only private-repository clone
   owned by `nextvisit-runner`, then run:

   ```bash
   cd infra/llm
   ./scripts/verify-host.sh gpu
   ```

4. In Cloudflare Zero Trust, create a remotely managed Tunnel route whose
   service is `http://ollama:11434`. Protect its public hostname with an Access
   service-token policy.
5. Store only the Tunnel token locally:

   ```bash
   sudo install -d -o root -g nextvisit-runner -m 0750 /etc/nextvisit
   sudo install -o nextvisit-runner -g nextvisit-runner -m 0600 /dev/null /etc/nextvisit/llm.env
   sudoedit /etc/nextvisit/llm.env
   sudo test "$(stat -c '%a' /etc/nextvisit/llm.env)" = 600
   sudo -u nextvisit-runner test -r /etc/nextvisit/llm.env
   ```

   The file has one line named `TUNNEL_TOKEN` whose value is issued by
   Cloudflare. Do not store Access credentials in this file.
6. Protect `main` in the repository settings by requiring pull-request review
   and the `LLM CI` checks before merge.
7. In `https://github.com/y-minion/wanted_Hackaton/settings/actions/runners`,
   create a repository-only Linux x64 runner, execute GitHub's generated setup
   commands as `nextvisit-runner`, add the `llm` custom label during
   `config.sh`, and install/start it with `sudo ./svc.sh install nextvisit-runner`
   followed by `sudo ./svc.sh start`.
8. Create the GitHub environment `llm-laptop`. Keep required reviewers on it
   if the account plan supports them.
9. Leave the repository variable `LLM_DEPLOY_ENABLED` absent or `false` until
   every preceding check passes. Set it to `true` only when automatic `main`
   deployments should begin.

## Manual production-equivalent start

```bash
cd infra/llm
export NEXTVISIT_LLM_ENV_FILE=/etc/nextvisit/llm.env
docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml up --detach --wait --build ollama
docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml run --rm model-init
./scripts/smoke-openai.sh http://127.0.0.1:11434/v1
docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml up --detach ollama cloudflared
ollama_id="$(docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml ps -q ollama)"
test -n "$ollama_id"
test "$(docker inspect -f '{{len .HostConfig.PortBindings}}' "$ollama_id")" = 0
```

The first start exposes only loopback for the local smoke. The final command
recreates Ollama with no published host port and connects it to `cloudflared`.

## Deferred hardware acceptance

After the final start, run `nvidia-smi`, then
`docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml exec ollama ollama ps`.
Record `100% GPU`, response time with the 2K context, behavior after a reboot,
runner service status, Tunnel reachability through Access, and volume reuse.
These results cannot be claimed from macOS and remain open until the laptop is ready.

## Secrets and logs

Never print the Tunnel token, Access service-token values, Authorization
headers, prompts, provider response bodies, generated sentences, or guardian
data. The smoke script reports only fixed status text.
