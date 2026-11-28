const poses = {
  idle: {
    tail: 'M28 45C19 46 11 49 5 54c6 1 11 0 16-2l-5 7c8-1 16-5 20-11Z',
    feet: 'M25 54l-2 4h-4m4 0 2 2m12-5-1 4h-4m4 0 3 1',
    wing: 'M30 33c-6-2-12 2-13 8-1 5 2 10 7 11 5 0 11-5 14-10 2-5-3-8-8-9Z',
    coverts: 'M29 35c-5-1-9 2-9 6 4 2 10 0 13-4-1-1-2-2-4-2Z',
    feathers: 'M23 49c4-1 8-5 10-8m-13 5c3 0 6-2 8-4',
    pupilX: 40,
  },
  point: {
    tail: 'M28 45C19 47 12 50 6 55c6 1 10-1 15-2l-5 6c8-1 16-5 20-11Z',
    feet: 'M27 54l-2 4h-4m4 0 2 2m12-6-2 4h-4m4 0 3 1',
    wing: 'M25 35c7-4 14 2 21 3 6 1 10-4 15-3-1 4-5 7-9 9 4-1 5 1 2 3-5 3-12 2-19 0-6 2-11 0-13-4-2-3 0-6 3-8Z',
    coverts: 'M25 37c5-3 11 0 16 3-5 2-11 3-15 2-2-1-2-4-1-5Z',
    feathers: 'M36 44c5 1 11 1 16-2m-6 5 5-1',
    pupilX: 41,
  },
  fly: {
    tail: 'M27 44C18 43 10 43 4 46c4 3 10 4 15 3l-5 5c8 0 16-3 21-7Z',
    feet: 'm29 54-3 3-4-1m4 1 1 2m11-5-3 3-4-1m4 1 1 2',
    wing: 'M33 35C29 27 19 24 13 23c-5-1-8-5-10-9-3 5 0 11 5 14-4-1-5 1-3 4 3 4 8 6 14 6-3 1-2 4 3 5 5 2 11-2 11-8Z',
    coverts: 'M29 35c-3-5-9-8-15-9 1 4 6 8 12 11 2 0 3-1 3-2Z',
    feathers: 'M9 30c3 3 7 5 12 6m-1 4 5 1',
    pupilX: 39.6,
  },
} as const;

export function KeshaBird({ pose = 'idle' }: { pose?: 'idle' | 'point' | 'fly' }) {
  const { tail, feet, wing, coverts, feathers, pupilX } = poses[pose];

  return (
    <svg
      aria-hidden="true"
      focusable="false"
      width="64"
      height="64"
      viewBox="0 0 64 64"
      pointerEvents="none"
      style={{ display: 'block' }}
    >
      <g transform="translate(2 2) scale(0.9375)" stroke="#452c37" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <path d={feet} fill="none" stroke="#784835" strokeWidth="2" />
        <path d={tail} fill="#8e3041" />
        <path
          d="M20 37c4-3 6-6 6-10-3-4-2-10 1-13-3-1-5-3-5-5 3 2 7 2 9 1 2-2 2-4 1-6 4 2 5 4 5 6 6 1 10 5 11 10 1 4-1 8-3 11 4 5 6 10 4 16-3 8-12 11-21 9-7-1-13-6-12-11 0-3 2-6 4-8Z"
          fill="#eb3f36"
        />
        <path d="M44 33c4 5 5 11-1 16-4 4-9 5-13 4 5-5 5-15 14-20Z" fill="#ffb6a5" stroke="none" />
        <path
          d="M44 20c5-2 14 0 17 6 3 5 0 10-5 11 1-4-3-7-7-7-2 0-3 1-5 1 3-4 3-8 0-11Z"
          fill="#f0ad42"
        />
        <path d="M47 22c4-1 9 1 11 3-4-1-7-1-10 0Z" fill="#ffe291" stroke="none" />
        <path d="M48 28c4-1 8 0 11 2" fill="none" strokeWidth="1.3" />
        <path
          d="M33 22c0-5 3-8 7-7s6 4 6 8c-1 4-4 6-8 5-3-1-5-3-5-6Z"
          fill="#fff0d8"
          strokeWidth="1.45"
        />
        <ellipse cx={pupilX} cy="22" rx="2.75" ry="3.55" fill="#452c37" stroke="none" />
        <circle cx={pupilX + 0.65} cy="20.8" r="0.8" fill="#fffaf0" stroke="none" />
        <path d="M31 15c2-2 5-3 7-2m-6 17 3 1" fill="none" strokeWidth="1.35" />
        <path d={wing} fill="#447d80" />
        <path d={coverts} fill="#8bb8a6" stroke="none" />
        <path d={feathers} fill="none" strokeWidth="1.25" />
        <path d="M26 34c-3 1-6 2-10 2l3 2-3 4c4 0 9-2 12-5Z" fill="#c52830" strokeWidth="1.5" />
        <path d="M25 29c6 4 14 5 20 1l2 4c-7 5-17 4-24 0Z" fill="#a91928" strokeWidth="1.5" />
        <path d="M27 33c5 2 11 3 16 0" fill="none" stroke="#ff9788" strokeWidth="1.3" />
        <path d="M25 32c-2-1-4 0-4 2s2 3 4 2 2-3 0-4Z" fill="#ef3124" strokeWidth="1.4" />
      </g>
    </svg>
  );
}
