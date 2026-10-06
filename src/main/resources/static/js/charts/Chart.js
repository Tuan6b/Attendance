document.addEventListener("DOMContentLoaded", function () {

    const ctx = document.getElementById("weeklyAttendanceChart");

    new Chart(ctx, {
        type: "bar",
        data: {
            labels: weeklyStatsData.map(item => item.dayName),
            datasets: [
                {
                    label: "Có mặt",
                    data: weeklyStatsData.map(item => item.present),
                    backgroundColor: "#2F6B4F",
                    borderRadius: 1
                },
                {
                    label: "Đi muộn",
                    data: weeklyStatsData.map(item => item.late),
                    backgroundColor: "#E0B54F",
                    borderRadius: 4
                },
                {
                    label: "Vắng mặt",
                    data: weeklyStatsData.map(item => item.absent),
                    backgroundColor: "#F2765E",
                    borderRadius: 2
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    display: true,
                    position: "top"
                }
            },
            scales: {
                y: {
                    beginAtZero: true,
                    ticks: {
                        stepSize: 1
                    }
                }
            }
        }
    });

}); 
