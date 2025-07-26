import React from 'react';
import ReactDOM from 'react-dom/client';
import './index.css';
import App from './App';

const root = ReactDOM.createRoot(
  document.getElementById('app') as HTMLElement
);

const urlParams = new URLSearchParams(window.location.search);
const commandId: string = urlParams.get('commandId') || '';

root.render(
  <React.StrictMode>
    <App commandId={commandId}/>
  </React.StrictMode>
);
