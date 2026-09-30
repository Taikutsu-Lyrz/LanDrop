use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use landrop_remote_codec::*;

#[test]
fn compact_and_json_invites_round_trip_for_remote_hosts() {
    let certificate = (0..96u8).collect::<Vec<_>>();
    let invite = RemoteInvite {
        version: REMOTE_PROTOCOL_VERSION,
        device_name: "Travel Laptop".to_string(),
        host: "203.0.113.90".to_string(),
        port: 50502,
        certificate_der: URL_SAFE_NO_PAD.encode(&certificate),
        certificate_fingerprint: certificate_fingerprint(&certificate),
        access_token: "remote-token-value-0123456789abcdef".to_string(),
    };

    let compact = encode_compact_invite(&invite, &certificate).expect("compact encode");
    let compact_decoded = decode_invite(&compact).expect("compact decode");
    assert_eq!(compact_decoded.host, "203.0.113.90");
    assert_eq!(compact_decoded.port, 50502);
    assert_eq!(compact_decoded.access_token, invite.access_token);

    let mut json_invite = invite.clone();
    json_invite.certificate_fingerprint = certificate_fingerprint(&certificate).to_uppercase();
    let json = encode_json_invite(&json_invite).expect("json encode");
    let json_decoded = decode_invite(&json).expect("json decode");
    assert_eq!(json_decoded.host, "203.0.113.90");
    assert_eq!(
        json_decoded.certificate_fingerprint,
        certificate_fingerprint(&certificate)
    );
}

#[test]
fn endpoint_parser_supports_cross_network_host_forms() {
    assert_eq!(
        parse_public_endpoint("home.example.org:44444", 50502).unwrap(),
        ("home.example.org".to_string(), 44444)
    );
    assert_eq!(
        parse_public_endpoint("[2001:db8::20]:50502", 50502).unwrap(),
        ("2001:db8::20".to_string(), 50502)
    );
    assert!(parse_public_endpoint("home.example.org:notaport", 50502).is_err());
    assert!(parse_public_endpoint("203.0.113.90:0", 50502).is_err());
}
