#!/usr/bin/env perl
# Copyright 2026 Gravity Compile
# SPDX-License-Identifier: Apache-2.0
#
# Text half of the white-label rename (tools/rebrand/rebrand.sh drives it; see README.md there).
#
#   rebrand.pl FILE...        rewrite FILEs in place (only files whose content changes are written)
#   rebrand.pl --name NAME    print the renamed form of a single path component (used for file renames)
#
# Every rule is a pure function of the input text and none of the outputs matches any input pattern,
# so running the script twice is a no-op (idempotent). The rules are ordered: specific before generic.
#
# What is deliberately NOT renamed (wire / attribution / third-party identity):
#   - upstream copyright lines ("Copyright 20xx LiveKit, Inc.") - Apache-2.0 4(c) attribution;
#   - URLs (docs.livekit.io, github.com/livekit/...) and LiveKit cloud host names - hand-edited or
#     listed as remaining references; the region host check is a hand edit (it changes behaviour);
#   - protobuf file names (livekit_*.proto live in the upstream `protocol` submodule) and the proto
#     `package livekit;` - only the generated JAVA names change (see gravix-proto-names.gradle);
#   - E2EE interop constants (ratchet salt "LKFrameEncryptionKey", magic bytes "LK-ROCKS") - every
#     other SDK derives keys from these exact strings;
#   - `lk.*` attribute/topic keys and their mirrored property names (lkAgentState ...) - wire names.
use strict;
use warnings;

my $MOD_NOTICE = 'Modifications Copyright 2026 Gravity Compile';

# Protected tokens: replaced by placeholders before the rules run, restored afterwards.
my @PROTECT = (
    qr{https?://[^\s"'`<>()\[\]{}]+},                     # URLs
    qr{github\.com/livekit/[A-Za-z0-9_.-]+},              # scheme-less repo references
    qr{[A-Za-z0-9.-]*livekit\.(?:cloud|run|io)\b},        # LiveKit hosts (region check = hand edit)
    qr{\blivekit_[a-z0-9_]+\.proto\b},                    # upstream proto file names
    qr{\@livekit/[A-Za-z0-9_-]+},                         # npm package names in comments
    qr{\bLKFrameEncryptionKey\b},                         # E2EE ratchet salt (interop)
    qr{\bLK-ROCKS\b},                                     # E2EE magic bytes (interop)
);

# Proto outer classes generated from livekit_<name>.proto (Java names only; see gravix-proto-names.gradle).
my $PROTO_OUTER = qr/(?:Models|Rtc|Metrics|TokenSource|Room|AgentDispatch|AgentSimulation|Agent|Egress|Ingress|Sip|Webhook|Analytics|Internal|CloudAgent|PhoneNumber|Connector\w*)\b/;

sub rename_text {
    my ($s, $prose) = @_;    # $prose: comment line / non-code file

    # --- WebRTC: LiveKit's prefixed libwebrtc build -> the unprefixed io.github.webrtc-sdk:android build
    $s =~ s/\blivekit\.org\.(webrtc|jni_zero)\b/org.$1/g;
    $s =~ s{\blivekit/org/(webrtc|jni_zero)\b}{org/$1}g;
    $s =~ s/\@livekit\.\*\*\.(CalledByNative\w*)/\@org.**.$1/g;          # proguard annotation rules
    $s =~ s/"lkjingle_peerconnection_so"/"jingle_peerconnection_so"/g;  # native library name
    $s =~ s/\bio\.github\.webrtc-sdk:android-prefixed\b/io.github.webrtc-sdk:android/g;

    # --- audioswitch: JitPack-only upstream dependency, vendored and relocated (tools/vendor/vendor-audioswitch.sh)
    $s =~ s/\bcom\.twilio\.audioswitch\b/com.gravixcloud.rtc.audioswitch/g;

    # --- Kotlin/Java packages
    $s =~ s/\bio\.livekit\.android\b/com.gravixcloud.rtc/g;
    $s =~ s{\bio/livekit/android\b}{com/gravixcloud/rtc}g;
    $s =~ s/\bio\.livekit\.(lint|detekt)\b/com.gravixcloud.rtc.$1/g;
    $s =~ s{\bio/livekit/(lint|detekt)\b}{com/gravixcloud/rtc/$1}g;
    $s =~ s/\bio\.livekit\b/com.gravixcloud/g;                           # Maven group
    $s =~ s{\bio/livekit\b}{com/gravixcloud}g;

    # --- protobuf Java names (package `livekit` -> `gravixcloud`, outer class Livekit* -> Gravixcloud*)
    $s =~ s/\blivekit\.Livekit(?=[A-Z])/gravixcloud.Gravixcloud/g;
    $s =~ s/Livekit(?=$PROTO_OUTER)/Gravixcloud/g;
    $s =~ s/\bimport livekit\./import gravixcloud./g;
    $s =~ s/\blivekit(?=\.\*\*)/gravixcloud/g;                            # proguard: livekit.**
    $s =~ s{(['"])livekit/\*\*}{$1gravixcloud/**}g;                       # jacoco excludes 'livekit/**'

    # --- Gradle modules, artifacts and build scripts
    $s =~ s/\blivekit-android-(sdk|test|camerax|track-processors)\b/gravix-rtc-android-$1/g;
    $s =~ s/\blivekit-(lint|detekt-rules)\b/gravix-rtc-$1/g;
    $s =~ s/\blivekit-protobuf\b/gravix-protobuf/g;
    $s =~ s/\blivekit-android\b(?!-)/gravix-rtc-android/g;

    # --- "LK" abbreviations (LKLog -> GxLog, LKObjects -> GxObjects, ...)
    $s =~ s/\bisLKCloud\b/isGravixCloud/g;
    $s =~ s/\bLK_RTC_THREAD\b/GX_RTC_THREAD/g;
    $s =~ s/\blkObjects\b/gxObjects/g;
    $s =~ s/(?<![A-Z])LK(?=[A-Z][a-z])/Gx/g;

    # --- "LiveKit"
    $s =~ s/\bLiveKit Cloud\b/Gravix Cloud/g;
    $s =~ s/LiveKit(?=[A-Za-z0-9_])/GravixRtc/g;                       # LiveKitOverrides -> GravixRtcOverrides
    if ($prose) {
        $s =~ s/\bLiveKit(?=\.[a-z])/GravixRtc/g;                         # LiveKit.create() in docs
        $s =~ s/\[LiveKit\]/[GravixRtc]/g;                                # KDoc links
        $s =~ s/`LiveKit`/`GravixRtc`/g;
        $s =~ s/\bLiveKit\b/Gravix/g;                                     # prose
    } else {
        $s =~ s/\bLiveKit\b/GravixRtc/g;                                  # the entry-point object
    }
    $s =~ s/Livekit/Gravix/g;                                           # LivekitRuleSetProvider, sample themes
    $s =~ s/LIVEKIT/GRAVIX/g;
    $s =~ s/\blivekit(?=[A-Z_])/gravix/g;                                 # livekitUrl, livekit_screen_capture
    $s =~ s/\blivekit\b/gravix/g;                                         # remaining ids / prose
    return $s;
}

sub transform_line {
    my ($line, $is_code) = @_;
    return $line if $line =~ /Copyright.*LiveKit, Inc\./;               # upstream attribution stays
    my @saved;
    for my $re (@PROTECT) {
        $line =~ s/($re)/push(@saved, $1); "\x{1}" . $#saved . "\x{2}"/ge;
    }
    my $prose = !$is_code || $line =~ m{^\s*(?:\*|//|/\*)};
    $line = rename_text($line, $prose);
    $line =~ s/\x{1}(\d+)\x{2}/$saved[$1]/g;
    return $line;
}

# Apache-2.0 4(b): modified files carry a prominent notice. Insert it right under the first copyright
# line of the file header (the LiveKit line when there is one), once.
sub add_mod_notice {
    my ($text) = @_;
    return $text if index($text, $MOD_NOTICE) >= 0 || $text =~ /Modifications Copyright/;
    my @lines = split /(?<=\n)/, $text;
    my $limit = @lines < 40 ? scalar(@lines) : 40;
    my $idx = -1;
    for my $i (0 .. $limit - 1) {
        if ($lines[$i] =~ /Copyright.*LiveKit, Inc\./) { $idx = $i; last; }
    }
    if ($idx < 0) {
        for my $i (0 .. $limit - 1) {
            if ($lines[$i] =~ /^\s*(?:\*|\/\/|#)?\s*Copyright\b/) { $idx = $i; last; }
        }
    }
    return $text if $idx < 0;                                             # no header: NOTICE covers it
    my ($prefix) = $lines[$idx] =~ /^(\s*(?:\*|\/\/|#)?\s*)/;
    $prefix = '' unless defined $prefix;
    splice @lines, $idx + 1, 0, $prefix . $MOD_NOTICE . "\n";
    return join '', @lines;
}

if (@ARGV && $ARGV[0] eq '--name') {
    my $name = $ARGV[1];
    print transform_line($name, 1), "\n";
    exit 0;
}

my $changed = 0;
for my $file (@ARGV) {
    open my $in, '<:raw', $file or die "rebrand.pl: cannot read $file: $!\n";
    my $orig = do { local $/; <$in> };
    close $in;
    my $is_code = $file =~ /\.(?:kt|kts|java)$/;
    my $out = join '', map { transform_line($_, $is_code) } split /(?<=\n)/, $orig;
    next if $out eq $orig;
    $out = add_mod_notice($out) if $is_code;
    open my $o, '>:raw', $file or die "rebrand.pl: cannot write $file: $!\n";
    print $o $out;
    close $o;
    $changed++;
}
print STDERR "rebrand.pl: rewrote $changed file(s)\n";
