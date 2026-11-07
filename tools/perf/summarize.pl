#!/usr/bin/perl
# Summarizes an atrace text capture of a Home press (see trace-home.sh):
#   - time from the FTV-HOME-KEY mark to FeatherTV's first frame after it resumed;
#   - the main thread's and RenderThread's slices in between (2 ms or more);
#   - how long those threads ran, waited for a CPU, slept or were blocked;
#   - which processes used the CPUs meanwhile.
# Usage: perl summarize.pl <trace> <FeatherTV pid>
use strict;
use warnings;

my ($file, $pid) = @ARGV;
die "usage: summarize.pl <trace> <pid>\n" unless $file && $pid;

my $LINE = qr/^\s*(.*)-(\d+)\s+(?:\(\s*([-\d]+)\)\s+)?\[(\d+)\]\s+\S+\s+([\d.]+):\s+([\w:]+):\s*(.*)$/;

# ---- pass 1: mark, threads, slices
my (%tgid, %comm, %stack, @slices, $key, $ncpu);
open my $in, '<', $file or die "$file: $!\n";
while (<$in>) {
    next unless /$LINE/;
    my ($c, $tid, $tg, $cpu, $ts, $ev, $args) = ($1, $2, $3, $4, $5, $6, $7);
    $comm{$tid} = $c;
    $tgid{$tid} = $tg if defined $tg && $tg ne '-----' && $tg =~ /^\d+$/;
    $ncpu = $cpu + 1 if !defined $ncpu || $cpu + 1 > $ncpu;
    next unless $ev eq 'tracing_mark_write';
    if ($args =~ /^FTV-HOME-KEY/) { $key //= $ts; next }
    if ($args =~ /^B\|(\d+)\|(.*)$/) {
        $tgid{$tid} //= $1;
        push @{$stack{$tid}}, [$2, $ts];
    } elsif ($args =~ /^E/) {
        my $s = pop @{$stack{$tid} || []} or next;
        push @slices, { tid => $tid, name => $s->[0], start => $s->[1], end => $ts, depth => scalar @{$stack{$tid}} };
    }
}
close $in;
die "no FTV-HOME-KEY mark in the trace\n" unless defined $key;

my $main = $pid;
my ($render) = grep { ($tgid{$_} // '') eq $pid && $comm{$_} eq 'RenderThread' } keys %comm;
my %target = ($main => 'main', ($render ? ($render => 'RenderThread') : ()));

my @mine = sort { $a->{start} <=> $b->{start} } grep { $target{$_->{tid}} && $_->{end} > $key } @slices;
my ($resume) = grep { $_->{tid} == $main && $_->{name} =~ /^activity(Resume|Start|Restart)/ } @mine;
my $after = $resume ? $resume->{start} : $key;
my ($frame) = grep { $render && $_->{tid} == $render && $_->{start} > $after
                     && $_->{name} =~ /^(queueBuffer|eglSwapBuffers)/ } @mine;
my $end = $frame ? $frame->{end} : $key + 3;
my $ms = sub { sprintf '%6.0f', ($_[0] - $key) * 1000 };

printf "Key -> FeatherTV resumed: %s ms\n", $resume ? $ms->($resume->{start}) : '     ?';
printf "Key -> first frame queued: %s ms%s\n\n", $ms->($end), $frame ? '' : ' (no frame found: window cut at 3 s)';

print "Slices of 2 ms or more (ms from the key):\n";
for my $s (@mine) {
    next if $s->{start} > $end || $s->{end} - $s->{start} < 0.002 || $s->{depth} > 3;
    printf "  %s +%5.0f  %-12s %s%s\n", $ms->($s->{start}), ($s->{end} - $s->{start}) * 1000,
        $target{$s->{tid}}, '  ' x $s->{depth}, $s->{name};
}

# ---- pass 2: thread states and CPU use in [key, end]
my (%state, %since, %acc, %cpuTime);
my $add = sub {
    my ($tid, $st, $from, $to) = @_;
    $from = $key if $from < $key;
    $to = $end if $to > $end;
    return if $to <= $from;
    $acc{$tid}{$st} += $to - $from if $target{$tid};
    $cpuTime{$tid} += $to - $from if $st eq 'running';
};
my $set = sub {
    my ($tid, $st, $ts) = @_;
    $add->($tid, $state{$tid}, $since{$tid}, $ts) if defined $state{$tid};
    ($state{$tid}, $since{$tid}) = ($st, $ts);
};
open $in, '<', $file or die "$file: $!\n";
while (<$in>) {
    next unless /$LINE/;
    my ($ts, $ev, $args) = ($5, $6, $7);
    last if $ts > $end;
    if ($ev eq 'sched_switch'
        && $args =~ /prev_comm=.* prev_pid=(\d+) prev_prio=\d+ prev_state=(\S+) ==> next_comm=(.*) next_pid=(\d+)/) {
        my ($prev, $pst, $next) = ($1, $2, $4);
        my $st = $pst =~ /^R/ ? 'runnable' : $pst =~ /^S/ ? 'sleeping' : $pst =~ /[DKWX]/ ? 'blocked' : 'sleeping';
        $set->($prev, $st, $ts) if $prev;
        $set->($next, 'running', $ts) if $next;
        $comm{$next} //= $3;
    } elsif ($ev =~ /^sched_wak(eup|eup_new|ing)$/ && $args =~ /comm=.* pid=(\d+) prio=/) {
        my $tid = $1;
        $set->($tid, 'runnable', $ts) if !defined $state{$tid} || $state{$tid} ne 'running';
    }
}
close $in;
$add->($_, $state{$_}, $since{$_}, $end) for grep { defined $state{$_} } keys %state;

print "\nThread states between the key and the first frame (ms):\n";
printf "  %-12s %8s %8s %8s %8s\n", '', 'running', 'waiting', 'sleeping', 'blocked';
for my $tid (sort { $a <=> $b } keys %target) {
    my $s = $acc{$tid} || {};
    printf "  %-12s %8.0f %8.0f %8.0f %8.0f\n", $target{$tid},
        map { ($s->{$_} // 0) * 1000 } qw(running runnable sleeping blocked);
}
print "  (waiting = runnable but without a CPU; blocked = uninterruptible, e.g. a driver or GPU wait)\n";

my %byProc;
for my $tid (keys %cpuTime) {
    my $tg = $tgid{$tid} // $tid;
    my $name = $comm{$tg} // $comm{$tid} // "?";
    $name = 'idle' if $tid == 0;
    $byProc{"$name ($tg)"} += $cpuTime{$tid};
}
my $span = ($end - $key) * ($ncpu // 1);
print "\nCPU use between the key and the first frame (", $ncpu // '?', " CPUs, ms):\n";
my $n = 0;
for my $p (sort { $byProc{$b} <=> $byProc{$a} } keys %byProc) {
    printf "  %7.0f  %4.0f%%  %s\n", $byProc{$p} * 1000, 100 * $byProc{$p} / $span, $p;
    last if ++$n == 12;
}
