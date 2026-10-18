import {execFileSync,spawn} from 'node:child_process';
import {mkdir,access} from 'node:fs/promises';

// Local self-signed development TLS identity, never a personal certificate.
await mkdir('.local/tls',{recursive:true});
try {await access('.local/tls/key.pem');await access('.local/tls/cert.pem');}
catch {
  execFileSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-days','30',
    '-keyout','.local/tls/key.pem','-out','.local/tls/cert.pem','-subj','/CN=localhost'],{stdio:'ignore'});
}
const child=spawn(process.execPath,['scripts/serve.mjs'],{env:{...process.env,HTTPS:'1',PORT:process.env.PORT||'4174'},stdio:'inherit'});
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>child.kill(signal));
child.on('exit',code=>{process.exitCode=code||0;});
