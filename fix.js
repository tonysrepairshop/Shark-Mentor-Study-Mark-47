const fs = require('fs');
let text = fs.readFileSync('C:/Users/hp/AndroidStudioProjects/SharkMentor/app/src/main/java/com/example/sharkmentor/ui/screens/HomeScreen.kt', 'utf-8');
const regex = /[ \t]*Crossfade\(targetState = isLoading, label = "HomeScreenCrossfade"\)\s*\{\s*loading ->\s*if \(loading\)\s*\{\s*Column\([\s\S]*?val shimmerBrush = rememberShimmerBrush\(\)[\s\S]*?\}\s*\}\s*\}\s*else\s*\{\s*Column\(/g;
const matches = text.match(regex);
console.log('Found ' + (matches ? matches.length : 0) + ' matches');
if (matches && matches.length > 0) {
    let newText = text.replace(regex, 'Column(');
    fs.writeFileSync('C:/Users/hp/AndroidStudioProjects/SharkMentor/app/src/main/java/com/example/sharkmentor/ui/screens/HomeScreen.kt', newText, 'utf-8');
    console.log('File updated');
}
