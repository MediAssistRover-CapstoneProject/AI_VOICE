// Regenerate the standalone mapping page after changing map-navigation.js/.css.
// Static API resources remain protected; this does not change SecurityConfig.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../main/resources/static');
const page = path.join(root, 'mapping.html');
let html = fs.readFileSync(page, 'utf8');
function embed(kind, file, tag, initial) {
  const begin = `<!-- BEGIN_NAVIGATION_${kind} -->`, end = `<!-- END_NAVIGATION_${kind} -->`;
  const content = fs.readFileSync(path.join(root, file), 'utf8');
  if (content.toLowerCase().includes(`</${tag}`)) throw Error('Unexpected closing tag in ' + file);
  const replacement = `${begin}\n<${tag}>\n${content}\n</${tag}>\n${end}`;
  const start = html.indexOf(begin), finish = html.indexOf(end);
  if (start >= 0 && finish > start) html = html.slice(0, start) + replacement + html.slice(finish + end.length);
  else if (html.includes(initial)) html = html.replace(initial, () => replacement);
  else throw Error('Missing embedding marker for ' + file);
}
embed('STYLE', 'map-navigation.css', 'style', '<link rel="stylesheet" href="/map-navigation.css">');
embed('SCRIPT', 'map-navigation.js', 'script', '<script src="/map-navigation.js"></script>');
const autoBegin='<!-- BEGIN_AUTONOMY_SCRIPT -->',autoEnd='<!-- END_AUTONOMY_SCRIPT -->';
const autoStart=html.indexOf(autoBegin),autoFinish=html.indexOf(autoEnd);
if(autoStart>=0&&autoFinish>autoStart){
  const source=fs.readFileSync(path.join(root,'mapping-autonomy.js'),'utf8');
  if(source.toLowerCase().includes('</script'))throw Error('Unexpected closing script tag');
  html=html.slice(0,autoStart)+autoBegin+'\n<script>\n'+source+'\n</script>\n'+autoEnd+html.slice(autoFinish+autoEnd.length);
}
fs.writeFileSync(page, html, 'utf8');
console.log('Embedded navigation CSS/JS into mapping.html; API authentication unchanged.');
