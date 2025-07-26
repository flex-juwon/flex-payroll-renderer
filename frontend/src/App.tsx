import React, { useState, useEffect } from 'react';
import logo from './logo.svg';
import './App.css';

interface AppProps {
  commandId: string;
}

const App: React.FC<AppProps> = ({ commandId }) => {
  const [data, setData] = useState({name: 'Anonymous'})

  useEffect(() => {
    getData(commandId)
  }, [])

  const getData = async (commandId: string) => {
    const response = await fetch(
        `/api/data?commandId=${commandId}`,
        {
          method: 'GET',
          headers: {'Accept': 'application/json'},
          redirect: 'manual',
        }
    )

    const data = await response.json()

    setData(data)
  }

  return (
    <div className="App">
      <header className="App-header">
        <img src={logo} className="App-logo" alt="logo" />
        <p>
          Hello { data.name }
        </p>
      </header>
    </div>
  );
}

export default App;
