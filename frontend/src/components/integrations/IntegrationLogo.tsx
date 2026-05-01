/**
 * Renders inline SVG brand logos for each integration.
 * Uses official brand colours; avoids external asset dependencies.
 */
interface IntegrationLogoProps {
  integration: string; // key: JIRA, CONFLUENCE, SERVICENOW, PAGERDUTY, GITHUB, SLACK, BITBUCKET
  size?: number;
}

export default function IntegrationLogo({integration, size = 28}: IntegrationLogoProps) {
  const key = integration.toUpperCase();

  switch (key) {
    case 'JIRA':
      return (
        <svg
          width={size}
          height={size}
          viewBox='0 0 32 32'
          fill='none'
          xmlns='http://www.w3.org/2000/svg'
        >
          {/* Jira blue gradient diamond mark */}
          <defs>
            <linearGradient
              id='jira-g1'
              x1='22'
              y1='4'
              x2='12'
              y2='16'
              gradientUnits='userSpaceOnUse'
            >
              <stop stopColor='#0052CC' />
              <stop offset='1' stopColor='#2684FF' />
            </linearGradient>
            <linearGradient
              id='jira-g2'
              x1='10'
              y1='16'
              x2='20'
              y2='28'
              gradientUnits='userSpaceOnUse'
            >
              <stop stopColor='#0052CC' />
              <stop offset='1' stopColor='#2684FF' />
            </linearGradient>
          </defs>
          <path
            d='M27.1 15.1L17.3 5.3 16 4l-12 12 5.4 5.4c.4.4 1 .4 1.4 0L16 16.2l4.8 4.8L27.1 15.1z'
            fill='url(#jira-g1)'
          />
          <path
            d='M16 16.2l-5.2 5.2c-.4.4-.4 1 0 1.4L16 28l12-12-6.2-6.2L16 16.2z'
            fill='url(#jira-g2)'
          />
          <path d='M16 10.6a7.6 7.6 0 010 10.8l5.2-5.2L16 10.6z' fill='#2684FF' opacity='0.65' />
        </svg>
      );

    case 'CONFLUENCE':
      return (
        <svg
          width={size}
          height={size}
          viewBox='0 0 32 32'
          fill='none'
          xmlns='http://www.w3.org/2000/svg'
        >
          {/* Confluence blue gradient double-arc mark */}
          <defs>
            <linearGradient
              id='conf-g1'
              x1='5'
              y1='24'
              x2='16'
              y2='12'
              gradientUnits='userSpaceOnUse'
            >
              <stop stopColor='#0052CC' />
              <stop offset='1' stopColor='#2684FF' />
            </linearGradient>
            <linearGradient
              id='conf-g2'
              x1='27'
              y1='8'
              x2='16'
              y2='20'
              gradientUnits='userSpaceOnUse'
            >
              <stop stopColor='#0052CC' />
              <stop offset='1' stopColor='#2684FF' />
            </linearGradient>
          </defs>
          <path
            d='M5.3 22.1s-.5-.8.3-1.4c2.6-2 5.4-3.1 8-2.2 2.2.8 3.4 3.2 6.6 3.6 2 .2 3.6-.4 4.5-.9.5-.3 1.2.2.8.8l-3.6 5.6c-.3.4-.7.6-1.2.5-1.8-.4-4.3-1.3-6.7-2.8-3-1.8-5.8-2.3-8.7-3.2z'
            fill='url(#conf-g1)'
          />
          <path
            d='M26.7 9.9s.5.8-.3 1.4c-2.6 2-5.4 3.1-8 2.2-2.2-.8-3.4-3.2-6.6-3.6-2-.2-3.6.4-4.5.9-.5.3-1.2-.2-.8-.8l3.6-5.6c.3-.4.7-.6 1.2-.5 1.8.4 4.3 1.3 6.7 2.8 3 1.8 5.8 2.3 8.7 3.2z'
            fill='url(#conf-g2)'
          />
        </svg>
      );

    case 'SERVICENOW':
      return (
        <svg
          width={size}
          height={size}
          viewBox='0 0 32 32'
          fill='none'
          xmlns='http://www.w3.org/2000/svg'
        >
          {/* ServiceNow green circle with inner ring */}
          <circle cx='16' cy='16' r='13' fill='#81B5A1' />
          <circle cx='16' cy='16' r='8' fill='none' stroke='#293E40' strokeWidth='2.5' />
          <circle cx='16' cy='16' r='3.5' fill='#293E40' />
        </svg>
      );

    case 'PAGERDUTY':
      return (
        <svg
          width={size}
          height={size}
          viewBox='0 0 32 32'
          fill='none'
          xmlns='http://www.w3.org/2000/svg'
        >
          {/* PagerDuty green bold "P" shape */}
          <rect x='4' y='4' width='24' height='24' rx='5' fill='#06AC38' />
          <path
            d='M12 8h5.5c3.3 0 5.5 2 5.5 5.2 0 3.2-2.2 5.2-5.5 5.2H15v5.6h-3V8zm3 7.8h2.3c1.7 0 2.7-.9 2.7-2.6 0-1.7-1-2.6-2.7-2.6H15v5.2z'
            fill='white'
          />
        </svg>
      );

    case 'SLACK':
      return (
        <svg
          width={size}
          height={size}
          viewBox='0 0 32 32'
          fill='none'
          xmlns='http://www.w3.org/2000/svg'
        >
          {/* Slack hash mark — 4 brand colours */}
          <path
            d='M8.8 19.2a2.4 2.4 0 01-2.4 2.4A2.4 2.4 0 014 19.2a2.4 2.4 0 012.4-2.4h2.4v2.4z'
            fill='#E01E5A'
          />
          <path
            d='M10 19.2a2.4 2.4 0 012.4-2.4 2.4 2.4 0 012.4 2.4v6a2.4 2.4 0 01-2.4 2.4 2.4 2.4 0 01-2.4-2.4v-6z'
            fill='#E01E5A'
          />
          <path
            d='M12.4 8.8a2.4 2.4 0 01-2.4-2.4A2.4 2.4 0 0112.4 4a2.4 2.4 0 012.4 2.4v2.4h-2.4z'
            fill='#36C5F0'
          />
          <path
            d='M12.4 10a2.4 2.4 0 012.4 2.4 2.4 2.4 0 01-2.4 2.4h-6A2.4 2.4 0 014 12.4 2.4 2.4 0 016.4 10h6z'
            fill='#36C5F0'
          />
          <path
            d='M23.2 12.4a2.4 2.4 0 012.4-2.4A2.4 2.4 0 0128 12.4a2.4 2.4 0 01-2.4 2.4h-2.4v-2.4z'
            fill='#2EB67D'
          />
          <path
            d='M22 12.4A2.4 2.4 0 0119.6 14.8a2.4 2.4 0 01-2.4-2.4v-6A2.4 2.4 0 0119.6 4 2.4 2.4 0 0122 6.4v6z'
            fill='#2EB67D'
          />
          <path
            d='M19.6 23.2a2.4 2.4 0 012.4 2.4A2.4 2.4 0 0119.6 28a2.4 2.4 0 01-2.4-2.4v-2.4h2.4z'
            fill='#ECB22E'
          />
          <path
            d='M19.6 22a2.4 2.4 0 01-2.4-2.4 2.4 2.4 0 012.4-2.4h6A2.4 2.4 0 0128 19.6 2.4 2.4 0 0125.6 22h-6z'
            fill='#ECB22E'
          />
        </svg>
      );

    case 'BITBUCKET':
      return (
        <svg
          width={size}
          height={size}
          viewBox='0 0 32 32'
          fill='none'
          xmlns='http://www.w3.org/2000/svg'
        >
          {/* Bitbucket blue shield mark */}
          <defs>
            <linearGradient
              id='bb-grad'
              x1='20'
              y1='28'
              x2='12'
              y2='13'
              gradientUnits='userSpaceOnUse'
            >
              <stop stopColor='#0052CC' />
              <stop offset='1' stopColor='#2684FF' />
            </linearGradient>
          </defs>
          <path
            d='M2.8 4.8h26.4a.8.8 0 01.8.94l-3.8 22.4a1.2 1.2 0 01-1.18 1.01H7.16a1.6 1.6 0 01-1.58-1.34L1.99 5.74a.8.8 0 01.81-.94z'
            fill='#2684FF'
          />
          <path d='M22.2 13.6h-5.4l-1.4 8.4h6.8l1.4-8.4z' fill='url(#bb-grad)' fillOpacity='.4' />
          <path d='M27.6 4.8H4.6l-1.4 8.8h7.2l.9-5.2h9.4l-.9 5.2h7.2l1.4-8.8z' fill='#2684FF' />
        </svg>
      );

    case 'GITHUB':
      return (
        <svg
          width={size}
          height={size}
          viewBox='0 0 32 32'
          fill='none'
          xmlns='http://www.w3.org/2000/svg'
        >
          {/* GitHub Octocat silhouette */}
          <path
            fillRule='evenodd'
            clipRule='evenodd'
            d='M16 3C8.82 3 3 8.82 3 16c0 5.74 3.72 10.62 8.89 12.34.65.12.89-.28.89-.63 0-.31-.01-1.13-.02-2.22-3.62.79-4.38-1.74-4.38-1.74-.59-1.5-1.44-1.9-1.44-1.9-1.18-.81.09-.79.09-.79 1.3.09 1.99 1.34 1.99 1.34 1.16 1.99 3.04 1.41 3.78 1.08.12-.84.45-1.41.82-1.74-2.89-.33-5.93-1.44-5.93-6.42 0-1.42.51-2.58 1.33-3.49-.13-.33-.58-1.65.13-3.44 0 0 1.09-.35 3.56 1.33a12.39 12.39 0 016.48 0c2.47-1.68 3.55-1.33 3.55-1.33.71 1.79.26 3.11.13 3.44.83.91 1.33 2.07 1.33 3.49 0 5-3.05 6.09-5.95 6.41.47.4.89 1.2.89 2.42 0 1.75-.02 3.16-.02 3.59 0 .35.24.76.9.63C25.28 26.62 29 21.74 29 16c0-7.18-5.82-13-13-13z'
            fill='#E6EDF3'
          />
        </svg>
      );

    default:
      // Generic gear icon fallback
      return (
        <svg
          width={size}
          height={size}
          viewBox='0 0 32 32'
          fill='none'
          xmlns='http://www.w3.org/2000/svg'
        >
          <circle cx='16' cy='16' r='11' stroke='#71717a' strokeWidth='2' />
          <circle cx='16' cy='16' r='4' fill='#71717a' />
        </svg>
      );
  }
}
