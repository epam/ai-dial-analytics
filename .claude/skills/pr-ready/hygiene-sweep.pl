#!/usr/bin/env perl
# Public-repo hygiene sweep (rules: CLAUDE.md "Public-repo hygiene"); this file holds the canonical patterns.
#   perl hygiene-sweep.pl <base>    sweep the lines HEAD adds over <base> (git diff <base>...HEAD)
#   perl hygiene-sweep.pl -         sweep stdin, e.g. gh pr view --json title,body -q '.title + "\n" + .body'
# Prints file:line: [rule] text for every hit. Exit 0 = clean, 1 = hits to judge, 2 = error.
use strict;
use warnings;
$SIG{__DIE__} = sub { print STDERR $_[0]; exit 2 };

my @LINE = (
    [env      => qr/(?<![\/\w])(?:[Dd]ev|UAT|[Pp]rod)\b(?!\/null)|DevOps/],
    [env      => qr/reference[- ]deployment|\bin production\b/i],
    [ticket   => qr/\bMR ?!\d+|(?<![\w\]!])!\d{2,}\b|\b(?!(?:UTF|ISO|SHA|RFC|UUID|HTTP|TLS|CVE|CWE|AES|RSA|HMAC|ECDSA|PBKDF)-)[A-Z]{2,}-\d{2,}\b/],
    [branch   => qr/\b(?:origin|upstream|github|gitlab)\/(?:development|main|master)\b|renumbered at merge/],
    [people   => qr/\bthe user (?:decided|asked|chose|wanted)\b/i],
    # Dates in prose ("on 2026-10-07"), not fixture dates, which are fine when invented.
    [date     => qr/\b(?:on|since|until|as of)\s+20\d\d-[01]\d-[0-3]\d(?:\b|T)|\b\d{1,2}:\d{2}(?::\d{2})? ?(?:UTC|CET|CEST|EET|EEST)\b/i],
    [machine  => qr{/Users/\w|/home/\w|\b[A-Za-z]:\\Users\\|~/\.\w|\.rd/docker\.sock}],
    [untracked => qr/\.local\.md\b/],
);
# Phrases checked again across line breaks, after comment prefixes are stripped.
my @WRAP = (
    [env    => qr/reference[-\s]+deployment|\bin\s+production\b/i],
    [people => qr/\bthe\s+user\s+(?:decided|asked|chose|wanted)\b/i],
    [branch => qr/renumbered\s+at\s+merge/],
);
my $SELF = '.claude/skills/pr-ready/hygiene-sweep.pl';

my $arg = shift // die "usage: perl hygiene-sweep.pl <base>|-\n";
my $in;
if ($arg eq '-') {
    $in = \*STDIN;
} else {
    # Pin the output format against user config (noprefix, external diff tools, quoted names).
    open($in, '-|', 'git', '-c', 'core.quotePath=false', 'diff', '-U0', '--no-color', '--no-ext-diff',
        '--src-prefix=a/', '--dst-prefix=b/', "$arg...HEAD") or die "git diff failed: $!\n";
}

my (%added, @order, $file, $ln);    # file => [[line, text], ...]
while (my $row = <$in>) {
    chomp $row;
    $row =~ s/\r$//;
    if ($arg eq '-') {
        $file //= '<stdin>';
        push @order, $file unless $added{$file};
        push @{ $added{$file} }, [$., $row];
        next;
    }
    if ($row =~ m{^\+\+\+ (?:"?b/(.*?)"?\t?|/dev/null)$}) { $file = $1; next; }
    if ($row =~ /^@@ -\S+ \+(\d+)/) { $ln = $1; next; }
    next unless defined $file && $file ne $SELF && $row =~ s/^\+//;
    push @order, $file unless $added{$file};
    push @{ $added{$file} }, [$ln++, $row];
}
close($in) or $arg eq '-' or die "git diff $arg...HEAD failed (fetch the base first?)\n";
die "empty input: nothing to sweep (did the command feeding stdin fail?)\n" if $arg eq '-' && !@order;

my $hits = 0;
for my $f (@order) {
    my @rows = @{ $added{$f} };
    for my $r (@rows) {
        for my $p (@LINE) {
            if ($r->[1] =~ $p->[1]) { print "$f:$r->[0]: [$p->[0]] $r->[1]\n"; $hits++; }
        }
    }
    # Join consecutive added lines; report only matches that cross a line break.
    my ($text, $prev, @starts) = ('', -1);
    for my $r (@rows) {
        (my $t = $r->[1]) =~ s{^\s*(?://+|/?\*+|#+|--)\s?}{};
        $text .= "\0" if $r->[0] != $prev + 1;    # a gap between hunks is not a line break to join over
        push @starts, [length $text, $r->[0]];
        $text .= "$t\n";
        $prev = $r->[0];
    }
    for my $p (@WRAP) {
        while ($text =~ /$p->[1]/g) {
            my ($s, $m) = ($-[0], $&);
            next unless $m =~ /\n/;
            my ($at) = grep { $_->[0] <= $s } reverse @starts;
            (my $flat = $m) =~ s/\s+/ /g;
            print "$f:$at->[1]: [$p->[0]] $flat\n";
            $hits++;
        }
    }
}
exit($hits ? 1 : 0);
