const fs = require('fs');
for (const file of ['reel_1.txt', 'reel_2.txt']) {
    try {
        const text = fs.readFileSync(file, 'utf8');
        // Match urls
        const urls = text.match(/https:\/\/[A-Za-z0-9\-\.\_\~\:\/\?\#\[\]\@\!\$\&\'\(\)\*\+\,\;\=\%]*fbcdn.net[A-Za-z0-9\-\.\_\~\:\/\?\#\[\]\@\!\$\&\'\(\)\*\+\,\;\=\%]*/g);
        if (urls) {
            console.log(`==== ${file} ====`);
            const uniqueUrls = [...new Set(urls)].filter(u => u.includes('mp4') || u.includes('video') || u.includes('bytestart'));
            console.log(`Found ${uniqueUrls.length} relevant urls. First 5:`);
            console.log(uniqueUrls.slice(0, 5).join('\n'));
        }
    } catch (e) {
        console.error(e);
    }
}
