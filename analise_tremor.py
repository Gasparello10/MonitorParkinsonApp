# --- Importações Essenciais ---
from flask import Flask, request, jsonify, render_template_string, send_file
import pandas as pd
import numpy as np
import matplotlib
matplotlib.use('Agg') # Modo não interativo, essencial para servidores web
import matplotlib.pyplot as plt
from scipy.fft import fft, fftfreq
import threading
import io
import socket
import time

# --- NOVAS Importações para Análise Avançada ---
from scipy.signal import butter, filtfilt, welch

# --- Configurações e Variáveis Globais ---
app = Flask(__name__)

# Constantes de Análise
TAXA_AMOSTRAGEM_HZ = 50  
TAMANHO_LOTE = 256  

# Armazenamento em memória
dados_recebidos = []
# Dicionário de resultados expandido para novas métricas
resultados_analise = {
    "freq_dominante": 0.0,
    "intensidade_rms": 0.0,  # <-- NOVA MÉTRICA
    "potencia_pico": 0.0,    # <-- NOVA MÉTRICA
    "plot_magnitude": None,
    "plot_fft": None,        # Este plot agora será o PSD
    "total_amostras": 0,
    "timestamp": 0
}
# Lock para garantir que o acesso à lista de dados seja seguro entre threads
dados_lock = threading.Lock()

# --- NOVAS Funções de Análise Avançada ---

def filtrar_sinal_passa_faixa(sinal, freq_corte_baixa, freq_corte_alta, taxa_amostragem):
    """Aplica um filtro passa-faixa Butterworth de ordem 5."""
    nyquist = 0.5 * taxa_amostragem
    low = freq_corte_baixa / nyquist
    high = freq_corte_alta / nyquist
    # b, a são os coeficientes do filtro
    b, a = butter(5, [low, high], btype='band')
    # filtfilt aplica o filtro para frente e para trás para evitar deslocamento de fase
    sinal_filtrado = filtfilt(b, a, sinal)
    return sinal_filtrado

def analisar_frequencia_com_welch(sinal_filtrado, taxa_amostragem):
    """
    Calcula o Espectro de Densidade de Potência (PSD) usando o método de Welch.
    Retorna as frequências, a potência e a frequência/potência do pico.
    """
    # nperseg pode ser ajustado, mas o tamanho do lote é um bom começo
    freqs, psd = welch(sinal_filtrado, taxa_amostragem, nperseg=min(len(sinal_filtrado), TAMANHO_LOTE))
    
    if len(psd) > 1:
        # Ignora a frequência de 0 Hz (DC offset) para encontrar o pico do tremor
        idx_pico = np.argmax(psd[1:]) + 1 
        freq_pico = freqs[idx_pico]
        potencia_pico = psd[idx_pico]
    else:
        freq_pico = 0.0
        potencia_pico = 0.0

    return freqs, psd, freq_pico, potencia_pico

def calcular_rms(sinal):
    """Calcula o Root Mean Square (RMS) do sinal."""
    return np.sqrt(np.mean(sinal**2))

# --- Função de Análise Principal (ATUALIZADA) ---

def analisar_lote_de_dados(lote_dados):
    """
    Função de análise aprimorada com filtragem, PSD e métricas de tempo.
    """
    global resultados_analise
    
    print(f"Iniciando análise aprimorada de um lote com {len(lote_dados)} amostras...")
    df = pd.DataFrame(lote_dados)
    if 'timestamp' not in df.columns or len(df) < 20: # Garante dados suficientes para o filtro
        print("Lote de dados inválido ou muito pequeno. Análise abortada.")
        return

    # 1. Calcular a magnitude e remover a média (componente DC)
    magnitude_bruta = np.sqrt(df['x']**2 + df['y']**2 + df['z']**2)
    sinal_bruto = magnitude_bruta - magnitude_bruta.mean()

    # 2. PRÉ-PROCESSAMENTO: Aplicar o filtro passa-faixa (3-8 Hz) para isolar o tremor
    sinal_filtrado = filtrar_sinal_passa_faixa(sinal_bruto.values, 3.0, 8.0, TAXA_AMOSTRAGEM_HZ)

    # 3. ANÁLISE DE TEMPO: Calcular RMS no sinal filtrado para medir a intensidade
    intensidade_rms = calcular_rms(sinal_filtrado)

    # 4. ANÁLISE DE FREQUÊNCIA: Usar o método de Welch no sinal filtrado
    freqs_psd, psd, freq_pico, potencia_pico = analisar_frequencia_com_welch(sinal_filtrado, TAXA_AMOSTRAGEM_HZ)

    print(f"Análise concluída. Freq. Pico: {freq_pico:.2f} Hz, Intensidade (RMS): {intensidade_rms:.4f}")

    # --- Geração de Gráficos Aprimorados ---
    
    # Gráfico de Magnitude (Sinal Filtrado vs. Bruto)
    fig_mag, ax_mag = plt.subplots(figsize=(10, 4))
    ax_mag.plot(sinal_bruto.index, sinal_bruto, color='gray', alpha=0.6, label='Sinal Bruto (sem gravidade)')
    ax_mag.plot(sinal_bruto.index, sinal_filtrado, color='purple', linewidth=1.5, label='Sinal de Tremor (Filtrado 3-8 Hz)')
    ax_mag.set_title('Magnitude do Movimento')
    ax_mag.set_xlabel('Amostras')
    ax_mag.set_ylabel('Amplitude')
    ax_mag.legend()
    ax_mag.grid(True)
    
    # Gráfico de PSD (em vez de FFT)
    fig_psd, ax_psd = plt.subplots(figsize=(10, 4))
    ax_psd.plot(freqs_psd, psd, color='blue')
    ax_psd.plot(freq_pico, potencia_pico, 'ro', markersize=8, label=f'Pico: {freq_pico:.2f} Hz')
    ax_psd.set_title('Análise de Frequência (PSD com Método de Welch)')
    ax_psd.set_xlabel('Frequência (Hz)')
    ax_psd.set_ylabel('Densidade de Potência')
    ax_psd.set_xlim(0, 15) # Foco nas frequências de interesse
    ax_psd.legend()
    ax_psd.grid(True)

    # Salvar gráficos em buffer
    buf_mag = io.BytesIO()
    fig_mag.savefig(buf_mag, format='png')
    buf_mag.seek(0)
    
    buf_psd = io.BytesIO()
    fig_psd.savefig(buf_psd, format='png')
    buf_psd.seek(0)
    
    plt.close(fig_mag)
    plt.close(fig_psd)

    # Atualizar resultados globais com as NOVAS métricas
    with dados_lock:
        resultados_analise['freq_dominante'] = freq_pico
        resultados_analise['intensidade_rms'] = intensidade_rms
        resultados_analise['potencia_pico'] = potencia_pico
        resultados_analise['plot_magnitude'] = buf_mag.getvalue()
        resultados_analise['plot_fft'] = buf_psd.getvalue() # Este é o plot do PSD
        resultados_analise['total_amostras'] = len(dados_recebidos)
        resultados_analise['timestamp'] = time.time()

# --- Endpoints da API Flask ---

@app.route('/data', methods=['POST'])
def receber_dados():
    """
    Recebe os dados do relógio. A cada 'TAMANHO_LOTE' de novas amostras,
    dispara uma nova thread para análise.
    """
    novo_dado = request.get_json()
    if not novo_dado:
        return jsonify({"status": "erro"}), 400

    with dados_lock:
        dados_recebidos.append(novo_dado)
        if len(dados_recebidos) > 0 and len(dados_recebidos) % TAMANHO_LOTE == 0:
            lote_para_analise = dados_recebidos[-TAMANHO_LOTE:]
            thread = threading.Thread(target=analisar_lote_de_dados, args=(lote_para_analise,))
            thread.start()
    
    return jsonify({"status": "sucesso"}), 201

# --- Endpoint de API ATUALIZADO para dados ---
@app.route('/api/latest_data')
def get_latest_data():
    """Retorna os últimos resultados da análise em formato JSON."""
    with dados_lock:
        # Retorna uma cópia para evitar problemas de concorrência
        data_to_send = {
            "freq_dominante": resultados_analise['freq_dominante'],
            "intensidade_rms": resultados_analise['intensidade_rms'], # <-- ENVIANDO NOVA MÉTRICA
            "total_amostras": resultados_analise['total_amostras'],
            "timestamp": resultados_analise['timestamp']
        }
    return jsonify(data_to_send)


# --- DASHBOARD COM HTML/JS ATUALIZADO ---
@app.route('/')
def dashboard():
    """
    Renderiza a página HTML do dashboard com JavaScript para atualizações dinâmicas.
    """
    html_template = """
    <!DOCTYPE html>
    <html lang="pt-br">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Dashboard de Monitoramento de Tremor</title>
        <style>
            body { font-family: sans-serif; background-color: #f4f4f9; color: #333; margin: 0; padding: 20px; }
            .container { max-width: 1000px; margin: auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
            h1 { color: #4a4a4a; text-align: center; }
            .metrics-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 20px; margin-bottom: 20px; }
            .metric { background: #eef; padding: 15px; border-radius: 8px; text-align: center; }
            .metric h2 { margin-top: 0; font-size: 1.2em; color: #4a4a4a;}
            .metric-value { font-size: 2.2em; font-weight: bold; color: #5a5a9e; }
            .plot-container { margin-top: 20px; }
            img { max-width: 100%; height: auto; margin-top: 15px; border: 1px solid #ddd; border-radius: 4px; }
            footer { text-align: center; margin-top: 20px; font-size: 0.9em; color: #777; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>Dashboard de Monitoramento de Tremor</h1>
            
            <div class="metrics-grid">
                <div class="metric">
                    <h2>Frequência de Pico</h2>
                    <p class="metric-value"><span id="freq-value">0.00</span> Hz</p>
                </div>
                <div class="metric">
                    <h2>Intensidade (RMS)</h2>
                    <p class="metric-value"><span id="rms-value">0.0000</span></p>
                </div>
                <div class="metric">
                    <h2>Amostras Recebidas</h2>
                    <p class="metric-value" id="samples-value">0</p>
                </div>
            </div>
            
            <div class="plot-container">
                <h2>Gráfico de Magnitude (Bruto vs. Filtrado)</h2>
                <img id="magnitude-plot" src="" alt="Aguardando dados...">
            </div>
            
            <div class="plot-container">
                <h2>Gráfico de Análise de Frequência (PSD)</h2>
                <img id="fft-plot" src="" alt="Aguardando dados...">
            </div>

            <footer id="footer-status">Conectando...</footer>
        </div>

        <script>
            function updateDashboard() {
                fetch('/api/latest_data')
                    .then(response => response.json())
                    .then(data => {
                        // Atualiza os valores de texto
                        document.getElementById('freq-value').innerText = data.freq_dominante.toFixed(2);
                        document.getElementById('rms-value').innerText = data.intensidade_rms.toFixed(4); // <-- ATUALIZA A NOVA MÉTRICA
                        document.getElementById('samples-value').innerText = data.total_amostras;

                        // Atualiza as fontes das imagens com um timestamp para evitar cache
                        if (data.timestamp > 0) {
                            // Adiciona o timestamp para que o navegador recarregue a imagem
                            document.getElementById('magnitude-plot').src = '/plot/magnitude.png?t=' + data.timestamp;
                            document.getElementById('fft-plot').src = '/plot/fft.png?t=' + data.timestamp;
                        }
                        
                        document.getElementById('footer-status').innerText = 'Atualizado em: ' + new Date().toLocaleTimeString();
                    })
                    .catch(error => {
                        console.error('Erro ao atualizar o dashboard:', error);
                        document.getElementById('footer-status').innerText = 'Erro de conexão. Tentando novamente...';
                    });
            }

            // Chama a função pela primeira vez e depois a cada 2 segundos
            document.addEventListener('DOMContentLoaded', () => {
                updateDashboard();
                setInterval(updateDashboard, 2000); // Atualiza a cada 2000 ms = 2 segundos
            });
        </script>
    </body>
    </html>
    """
    return render_template_string(html_template)

@app.route('/plot/<plot_name>.png')
def get_plot(plot_name):
    """Serve as imagens dos gráficos gerados."""
    with dados_lock:
        if plot_name == 'magnitude' and resultados_analise['plot_magnitude']:
            return send_file(io.BytesIO(resultados_analise['plot_magnitude']), mimetype='image/png')
        elif plot_name == 'fft' and resultados_analise['plot_fft']:
            return send_file(io.BytesIO(resultados_analise['plot_fft']), mimetype='image/png')
        else:
            # Retorna uma imagem de placeholder se o gráfico ainda não foi gerado
            try:
                placeholder = io.BytesIO()
                fig, ax = plt.subplots(figsize=(10,4))
                ax.text(0.5, 0.5, 'Aguardando dados para análise...', ha='center', va='center', fontsize=16)
                fig.savefig(placeholder, format='png')
                plt.close(fig)
                placeholder.seek(0)
                return send_file(placeholder, mimetype='image/png')
            except Exception as e:
                print(f"Erro ao gerar placeholder: {e}")
                return "Erro", 500

def get_ip_address():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('10.255.255.255', 1))
        IP = s.getsockname()[0]
    except Exception:
        IP = '127.0.0.1'
    finally:
        s.close()
    return IP

if __name__ == '__main__':
    ip_address = get_ip_address()
    print("=========================================================")
    print("  Servidor de Análise de Tremor Iniciado (Aprimorado)")
    print("=========================================================")
    print(f"-> Acesse o Dashboard em: http://{ip_address}:5000/")
    print(f"-> Envie dados do relógio para: http://{ip_address}:5000/data")
    print("=========================================================")
    app.run(host='0.0.0.0', port=5000, debug=True, use_reloader=False)