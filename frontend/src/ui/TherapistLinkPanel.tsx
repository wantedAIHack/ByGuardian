import { useState } from 'react';
import { useIssueLink } from '../lib/queries';
import { Button } from './Button';
import { Notice } from './Notice';

/**
 * 치료사용 요약 링크를 발급해 보여준다.
 * API는 `/t/{token}` 상대 경로를 주므로 절대 주소는 프론트가 자기 오리진으로 조립한다.
 */
export function TherapistLinkPanel() {
  const issue = useIssueLink();
  const [copied, setCopied] = useState(false);

  const url = issue.data ? `${window.location.origin}/t/${issue.data.token}` : null;

  if (!url) {
    return (
      <div>
        <Button variant="plain" disabled={issue.isPending} onClick={() => issue.mutate()}>
          {issue.isPending ? '만드는 중입니다…' : '치료사에게 보여드리기'}
        </Button>
        {issue.isError ? <Notice>링크를 만들지 못했습니다. 잠시 후 다시 눌러주세요.</Notice> : null}
      </div>
    );
  }

  return (
    <div className="rounded-lg border border-line p-4">
      <p className="text-small text-ink-soft">치료사에게 이 주소를 보여드리세요.</p>
      <p className="break-all pt-2">{url}</p>
      <div className="flex flex-col gap-3 pt-4">
        <Button
          variant="plain"
          onClick={() => {
            void navigator.clipboard?.writeText(url).then(() => setCopied(true));
          }}
        >
          {copied ? '복사했습니다' : '주소 복사하기'}
        </Button>
        <a className="btn btn-quiet" href={url} target="_blank" rel="noreferrer">
          어떻게 보이는지 확인하기
        </a>
      </div>
      <Notice>새로 만들면 먼저 드린 주소는 열리지 않습니다.</Notice>
    </div>
  );
}
