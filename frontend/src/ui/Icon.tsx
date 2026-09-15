const paths = {
  calendar: 'M5 5h14v15H5z M5 10h14 M8 3v4 M16 3v4',
  notebook: 'M6 3h13v18H6z M3 7h5 M3 12h5 M3 17h5 M11 8h5 M11 12h5',
  question: 'M5 4h14v12H9l-4 4z M10 8a2 2 0 0 1 4 0c0 2-2 1-2 3 M12 13v1',
  share: 'M12 3v12 M8 7l4-4 4 4 M5 12v8h14v-8',
} as const;

/** 장식 아이콘. 의미는 함께 놓인 제목·링크의 글자 라벨이 전달한다. */
export function Icon({ name }: { name: keyof typeof paths }) {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round"
      aria-hidden="true" focusable="false" className="shrink-0">
      <path d={paths[name]} />
    </svg>
  );
}
