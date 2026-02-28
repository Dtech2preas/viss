function customAlert(message) {
    return new Promise((resolve) => {
        const modal = document.createElement('div');
        modal.className = 'custom-modal';
        modal.innerHTML = `
            <div class="custom-modal-content">
                <p class="custom-modal-text"></p>
                <button class="custom-modal-btn">OK</button>
            </div>
        `;
        modal.querySelector('.custom-modal-text').innerText = message;
        document.body.appendChild(modal);
        modal.querySelector('button').onclick = () => {
            document.body.removeChild(modal);
            resolve();
        };
    });
}

function customConfirm(message) {
    return new Promise((resolve) => {
        const modal = document.createElement('div');
        modal.className = 'custom-modal';
        modal.innerHTML = `
            <div class="custom-modal-content">
                <p class="custom-modal-text"></p>
                <div class="custom-modal-buttons">
                    <button class="custom-modal-btn cancel">Cancel</button>
                    <button class="custom-modal-btn confirm">OK</button>
                </div>
            </div>
        `;
        modal.querySelector('.custom-modal-text').innerText = message;
        document.body.appendChild(modal);
        modal.querySelector('.cancel').onclick = () => {
            document.body.removeChild(modal);
            resolve(false);
        };
        modal.querySelector('.confirm').onclick = () => {
            document.body.removeChild(modal);
            resolve(true);
        };
    });
}

function customPrompt(message, defaultValue = '') {
    return new Promise((resolve) => {
        const modal = document.createElement('div');
        modal.className = 'custom-modal';
        modal.innerHTML = `
            <div class="custom-modal-content">
                <p class="custom-modal-text"></p>
                <input type="text" class="custom-modal-input">
                <div class="custom-modal-buttons">
                    <button class="custom-modal-btn cancel">Cancel</button>
                    <button class="custom-modal-btn confirm">OK</button>
                </div>
            </div>
        `;
        modal.querySelector('.custom-modal-text').innerText = message;
        const input = modal.querySelector('input');
        input.value = defaultValue;
        document.body.appendChild(modal);
        input.focus();
        modal.querySelector('.cancel').onclick = () => {
            document.body.removeChild(modal);
            resolve(null);
        };
        modal.querySelector('.confirm').onclick = () => {
            document.body.removeChild(modal);
            resolve(input.value);
        };
    });
}

// Override native functions ONLY for alert.
// For confirm/prompt, we don't globally override to avoid breaking sync callers.
window.appAlert = function(message) {
    return customAlert(message);
};

window.appConfirm = async function(message) {
    return await customConfirm(message);
};

window.appPrompt = async function(message, defaultValue) {
    return await customPrompt(message, defaultValue);
};

const style = document.createElement('style');
style.innerHTML = \`
.custom-modal {
    position: fixed;
    top: 0; left: 0; width: 100%; height: 100%;
    background: rgba(0, 0, 0, 0.4);
    display: flex; justify-content: center; align-items: center;
    z-index: 10000;
    backdrop-filter: blur(2px);
    font-family: 'Montserrat', sans-serif;
}
.custom-modal-content {
    background: #fff5f9;
    padding: 25px;
    border-radius: 20px;
    text-align: center;
    box-shadow: 0 10px 30px rgba(255, 126, 185, 0.2);
    min-width: 280px;
    max-width: 80%;
    border: 2px solid #ff7eb9;
}
.custom-modal-content p {
    font-size: 1.1rem;
    color: #555;
    margin-bottom: 20px;
}
.custom-modal-input {
    width: 90%;
    padding: 10px;
    margin-bottom: 20px;
    border: 1px solid #ff7eb9;
    border-radius: 10px;
    font-family: 'Montserrat', sans-serif;
    font-size: 1rem;
}
.custom-modal-buttons {
    display: flex; justify-content: center; gap: 15px;
}
.custom-modal-btn {
    background: linear-gradient(45deg, #ff7eb9, #ff65a3);
    color: white;
    border: none;
    padding: 10px 25px;
    border-radius: 50px;
    cursor: pointer;
    font-weight: 600;
    transition: 0.2s;
    font-family: 'Montserrat', sans-serif;
}
.custom-modal-btn:hover {
    transform: translateY(-2px);
    box-shadow: 0 5px 15px rgba(255,126,185,0.4);
}
.custom-modal-btn.cancel {
    background: #ccc;
    color: #333;
}
.custom-modal-btn.cancel:hover {
    box-shadow: 0 5px 15px rgba(0,0,0,0.1);
}
\`;
document.head.appendChild(style);
