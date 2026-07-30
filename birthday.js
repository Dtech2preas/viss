function checkBirthday() {
    const today = new Date();
    const isBirthday = (today.getMonth() === 7 && today.getDate() === 1 && today.getFullYear() === 2026);
    const isPreview = localStorage.getItem('previewBirthday') === 'true';
    if (isBirthday || isPreview) {
        enableBirthdayMode();
    }
}
function enableBirthdayMode() {
    if (!document.getElementById('birthday-css')) {
        const link = document.createElement('link'); link.id = 'birthday-css'; link.rel = 'stylesheet'; link.href = 'birthday.css'; document.head.appendChild(link);
    }
    if (!document.getElementById('birthday-banner')) {
        const banner = document.createElement('div'); banner.id = 'birthday-banner'; banner.innerHTML = "🎉 Happy 20th Birthday Owami! ❤️"; document.body.prepend(banner);
    }
    initFloatingHearts();
}
function initFloatingHearts() {
    if (document.getElementById('hearts-container')) return;
    const container = document.createElement('div'); container.id = 'hearts-container'; document.body.appendChild(container);
    setInterval(() => {
        const heart = document.createElement('div'); heart.className = 'floating-heart'; heart.innerHTML = '❤️'; heart.style.left = Math.random() * 100 + 'vw'; heart.style.animationDuration = Math.random() * 3 + 2 + 's'; container.appendChild(heart); setTimeout(() => { heart.remove(); }, 5000);
    }, 500);
}
document.addEventListener('DOMContentLoaded', checkBirthday);
