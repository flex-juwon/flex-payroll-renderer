import React from 'react';
import ReactDOM from 'react-dom/client';
import './index.css';
import App from './App';

const root = ReactDOM.createRoot(
  document.getElementById('app') as HTMLElement
);

const urlParams = new URLSearchParams(window.location.search);
const name = urlParams.get('name') || 'Anonymous';

root.render(
  <React.StrictMode>
    <App name={name}/>
  </React.StrictMode>
);
