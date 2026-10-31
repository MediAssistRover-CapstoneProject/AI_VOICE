"""Create a private LAN CA and an HTTPS server certificate; preserve an existing CA."""
import argparse
from datetime import datetime, timedelta, timezone
import ipaddress
from pathlib import Path
import re
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID


def write_key(path, key):
    path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                      serialization.NoEncryption()))
    path.chmod(0o600)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--server-name',action='append',required=True,help='IP/DNS reachable from Pi; repeat for multiple names')
    p.add_argument('--directory',type=Path,default=Path(__file__).resolve().parents[1]/'secrets/tls')
    p.add_argument('--renew-server',action='store_true',help='Replace server certificate; retain the LAN CA')
    a=p.parse_args();names=[]
    for value in dict.fromkeys(a.server_name):
        try:names.append(x509.IPAddress(ipaddress.ip_address(value)))
        except ValueError:
            if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]{0,252}',value):p.error('Use an IP/DNS name, without scheme, path or port')
            names.append(x509.DNSName(value))
    folder=a.directory.resolve();folder.mkdir(parents=True,exist_ok=True)
    ca_key_file=folder/'ca-key.pem';ca_file=folder/'ca.pem'
    if ca_key_file.exists()!=ca_file.exists():p.error('Incomplete CA pair; inspect the existing files before continuing')
    now=datetime.now(timezone.utc)
    if ca_file.exists():
        ca=x509.load_pem_x509_certificate(ca_file.read_bytes())
        ca_key=serialization.load_pem_private_key(ca_key_file.read_bytes(),password=None)
        if ca.public_key().public_numbers()!=ca_key.public_key().public_numbers():p.error('CA key does not match certificate')
        if ca.not_valid_after_utc <= now+timedelta(days=365):p.error('CA is near expiry; plan an explicit CA rotation')
    else:
        ca_key=rsa.generate_private_key(public_exponent=65537,key_size=3072)
        subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'MediAssistRover LAN CA')])
        ca=(x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(ca_key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(minutes=5)).not_valid_after(now+timedelta(days=3650))
            .add_extension(x509.BasicConstraints(ca=True,path_length=0),critical=True)
            .add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()),critical=False)
            .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()),critical=False)
            .add_extension(x509.KeyUsage(digital_signature=True,content_commitment=False,key_encipherment=False,data_encipherment=False,key_agreement=False,key_cert_sign=True,crl_sign=True,encipher_only=False,decipher_only=False),critical=True)
            .sign(ca_key,hashes.SHA256()))
        write_key(ca_key_file,ca_key);ca_file.write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    certificate_file=folder/'fullchain.pem';key_file=folder/'privkey.pem'
    if certificate_file.exists() or key_file.exists():
        if not (certificate_file.exists() and key_file.exists()):p.error('Incomplete server certificate/key pair')
        old=x509.load_pem_x509_certificate(certificate_file.read_bytes())
        san=old.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
        if not a.renew_server:
            if any(name not in san for name in names) or old.not_valid_after_utc<=now:p.error('Server name changed/certificate expired; use --renew-server to retain CA and renew server files')
            print('Existing server certificate retained:',certificate_file);return
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    server=(x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,a.server_name[0])]))
        .issuer_name(ca.subject).public_key(key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(now-timedelta(minutes=5)).not_valid_after(now+timedelta(days=365))
        .add_extension(x509.SubjectAlternativeName(names),critical=False)
        .add_extension(x509.BasicConstraints(ca=False,path_length=None),critical=True)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()),critical=False)
        .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()),critical=False)
        .add_extension(x509.KeyUsage(digital_signature=True,content_commitment=False,key_encipherment=True,data_encipherment=False,key_agreement=False,key_cert_sign=False,crl_sign=False,encipher_only=False,decipher_only=False),critical=True)
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]),critical=False)
        .sign(ca_key,hashes.SHA256()))
    write_key(key_file,key)
    certificate_file.write_bytes(server.public_bytes(serialization.Encoding.PEM)+ca.public_bytes(serialization.Encoding.PEM))
    print('Server certificate:',certificate_file)
    print('Copy only this public CA to Pi:',ca_file)
    print('Keep ca-key.pem and privkey.pem on the laptop/server.')


if __name__=='__main__':main()
