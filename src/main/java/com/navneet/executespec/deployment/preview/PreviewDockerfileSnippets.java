package com.navneet.executespec.deployment.preview;

final class PreviewDockerfileSnippets {

  private PreviewDockerfileSnippets() {}

  static String cloudRunNonRootUser(boolean alpine) {
    if (alpine) {
      return "RUN addgroup -S app && adduser -S app -G app && chown -R app:app /app\n"
          + "USER app\n";
    }
    return "RUN groupadd -r app && useradd -r -g app -s /sbin/nologin app && chown -R app:app"
        + " /app\n"
        + "USER app\n";
  }

  static String viteAllowedHostsPatch() {
    return "RUN node -e \"const fs=require('fs'),path=require('path');const"
               + " dirs=['.','frontend','client','src','app','web'];const"
               + " names=['vite.config.js','vite.config.ts','vite.config.mjs'];let"
               + " found=false;for(const d of dirs){  for(const n of names){    const"
               + " f=path.join(d,n);    if(fs.existsSync(f)){      found=true;      let"
               + " c=fs.readFileSync(f,'utf8');      if(!c.includes('allowedHosts')){       "
               + " c=c.replace(/server\\\\s*:\\\\s*\\\\{/,'server:{allowedHosts:true,');       "
               + " if(!c.includes('allowedHosts'))c=c.replace(/export\\\\s+default\\\\s*\\\\{/,'export"
               + " default{server:{allowedHosts:true},');       "
               + " if(!c.includes('allowedHosts'))c=c.replace(/defineConfig\\\\s*\\\\(\\\\s*\\\\{/,'defineConfig({server:{allowedHosts:true},');"
               + "       "
               + " if(!c.includes('allowedHosts'))c=c.replace(/=>\\\\s*\\\\(\\\\s*\\\\{/,'=>({server:{allowedHosts:true},');"
               + "       "
               + " if(!c.includes('allowedHosts'))c=c.replace(/plugins\\\\s*:/,'server:{allowedHosts:true},plugins:');"
               + "        fs.writeFileSync(f,c);      }      break;    }  } "
               + " if(found)break;}if(!found){  const"
               + " feDir=['frontend','client'].find(d=>fs.existsSync(path.join(d,'package.json')))||'.';"
               + "  const pkgPath=path.join(feDir,'package.json');  let hasViteDirect=false; "
               + " try{const"
               + " p=JSON.parse(fs.readFileSync(pkgPath,'utf8'));hasViteDirect=!!((p.dependencies&&p.dependencies.vite)||(p.devDependencies&&p.devDependencies.vite));}catch(e){}"
               + "  if(hasViteDirect){   "
               + " fs.writeFileSync(path.join(feDir,'vite.config.js'),'import{defineConfig}from\\\\\"vite\\\\\";export"
               + " default defineConfig({server:{allowedHosts:true}});');  }}\"\n";
  }

  static String viteProxyPatch(int port) {
    return "RUN node -e \""
        + "const fs=require('fs'),path=require('path');"
        + "const dirs=['.','frontend','client','src','app','web'];"
        + "const names=['vite.config.js','vite.config.ts','vite.config.mjs'];"
        + "for(const d of dirs){"
        + "  for(const n of names){"
        + "    const f=path.join(d,n);"
        + "    if(fs.existsSync(f)){"
        + "      let c=fs.readFileSync(f,'utf8');"
        + "      c=c.replace(/localhost:\\\\d{4}/g,'localhost:"
        + port
        + "');"
        + "      c=c.replace(/127\\\\.0\\\\.0\\\\.1:\\\\d{4}/g,'127.0.0.1:"
        + port
        + "');"
        + "      fs.writeFileSync(f,c);"
        + "      process.exit(0);"
        + "    }"
        + "  }"
        + "}\"\n";
  }

  static String viteDevScriptPatch(int vitePort) {
    return "RUN node -e \""
        + "const fs=require('fs');const p=JSON.parse(fs.readFileSync('package.json','utf8'));"
        + "if(p.scripts){"
        + "  for(const[k,v]of Object.entries(p.scripts)){"
        + "    if(typeof v==='string'&&/\\\\bvite\\\\b/.test(v)&&!v.includes('--host')){"
        + "      p.scripts[k]=v.replace(/\\\\bvite\\\\b/g,'vite --host 0.0.0.0 --port "
        + vitePort
        + "')    }  }  const"
        + " hasServer=p.scripts.server||p.scripts.backend||fs.existsSync('server/server.js')||fs.existsSync('server.js');"
        + "  const"
        + " runsBoth=Object.values(p.scripts).some(v=>v.includes('concurrently')||(v.includes('vite')&&v.includes('node')));"
        + "  if(hasServer && !runsBoth){    const serverCmd=p.scripts.server?'npm run"
        + " server':(p.scripts.backend?'npm run backend':(fs.existsSync('server/server.js')?'node"
        + " server/server.js':'node server.js'));    p.scripts['dev:all']='concurrently \\\"npm run"
        + " dev\\\" \\\"'+serverCmd+'\\\"'; "
        + " }}fs.writeFileSync('package.json',JSON.stringify(p,null,2));\"\n";
  }
}
