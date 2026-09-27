const fs = require('fs');
for (const file of ['reel_1.txt']) {
    try {
        const text = fs.readFileSync(file, 'utf8');
        let idx1 = text.indexOf('playable_url');
        let idx2 = text.indexOf('browser_native');
        if (idx1 !== -1) console.log('playable_url:', text.substring(idx1, idx1+500));
        if (idx2 !== -1) console.log('browser_native:', text.substring(idx2, idx2+500));
        
        // Find any fbcdn url with .mp4
        const matches = text.match(/https:\\\/\\\/[a-zA-Z0-9.\-\_]*?\/?fbcdn[-a-zA-Z0-9.\-\_]*?\\?\/[^"',]*/g);
        if (matches) {
            let res = matches.filter(x => x.includes('.mp4') || x.includes('video') || x.includes('bytestart'));
            console.log(res.slice(0,5));
        }
    } catch (e) {
        console.error(e);
    }
}
