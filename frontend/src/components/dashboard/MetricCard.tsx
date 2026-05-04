import {AreaChart, Area, ResponsiveContainer} from 'recharts';
import GlassPanel from '@/components/shared/GlassPanel';
import AnimatedCounter from '@/components/shared/AnimatedCounter';
import {} from '@/lib/utils';

interface MetricCardProps {
  label: string;
  value: number;
  format?: (n: number) => string;
  sparkData?: {v: number}[];
  accent?: string;
  pulse?: boolean;
}

export default function MetricCard({
  label,
  value,
  format,
  sparkData,
  accent = '#8B5CF6',
  pulse,
}: MetricCardProps) {
  return (
    <GlassPanel className='p-4 flex flex-col gap-2 relative overflow-hidden'>
      <p className='text-xs text-muted font-medium uppercase tracking-wider'>{label}</p>
      <div className='flex items-end justify-between gap-2'>
        <AnimatedCounter
          value={value}
          format={format}
          className='text-2xl font-bold text-primary font-sans'
        />
        {pulse && value > 0 && (
          <span className='relative flex h-2.5 w-2.5 mb-1'>
            <span
              className='animate-ping absolute inline-flex h-full w-full rounded-full opacity-75'
              style={{background: accent}}
            />
            <span
              className='relative inline-flex rounded-full h-2.5 w-2.5'
              style={{background: accent}}
            />
          </span>
        )}
      </div>
      {sparkData && sparkData.length > 0 && (
        <div className='h-10 -mx-4 -mb-4 mt-1'>
          <ResponsiveContainer width='100%' height='100%'>
            <AreaChart data={sparkData} margin={{top: 0, right: 0, left: 0, bottom: 0}}>
              <defs>
                <linearGradient id={`grad-${label}`} x1='0' y1='0' x2='0' y2='1'>
                  <stop offset='5%' stopColor={accent} stopOpacity={0.3} />
                  <stop offset='95%' stopColor={accent} stopOpacity={0} />
                </linearGradient>
              </defs>
              <Area
                type='monotone'
                dataKey='v'
                stroke={accent}
                strokeWidth={1.5}
                fill={`url(#grad-${label})`}
                dot={false}
                animationDuration={800}
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      )}
    </GlassPanel>
  );
}
